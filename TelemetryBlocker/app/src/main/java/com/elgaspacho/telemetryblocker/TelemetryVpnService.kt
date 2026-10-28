package com.elgaspacho.telemetryblocker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local VPN applied only to the apps the user selected, in one of two modes per app:
 *
 * - telemetry: queries for telemetry domains get an NXDOMAIN answer, the others are
 *   forwarded to the real DNS servers;
 * - block everything: every query gets NXDOMAIN.
 *
 * Normally the tunnel advertises a fake DNS server and only routes that address, so the
 * apps keep using the normal network for everything else. When every selected app is in
 * "block everything" mode, the tunnel routes all traffic instead and drops whatever is not
 * DNS ([firewallMode]), which also stops connections made to hard-coded IP addresses.
 */
class TelemetryVpnService : VpnService() {

    companion object {
        private const val TAG = "TelemetryVpn"
        private const val ACTION_START = "com.elgaspacho.telemetryblocker.START"
        private const val ACTION_STOP = "com.elgaspacho.telemetryblocker.STOP"
        private const val ACTION_RELOAD = "com.elgaspacho.telemetryblocker.RELOAD"

        private const val TUN_ADDR4 = "10.111.222.1"
        private const val FAKE_DNS4 = "10.111.222.2"
        private const val TUN_ADDR6 = "fd00:7e1e:7e1e::1"
        private const val FAKE_DNS6 = "fd00:7e1e:7e1e::2"
        private const val MTU = 4096
        private const val MAX_DNS_SIZE = MTU - 48 // room for IPv6 + UDP headers
        private const val UPSTREAM_TIMEOUT_MS = 4000
        private val FALLBACK_DNS = listOf("1.1.1.1", "9.9.9.9")
        private const val DROP_LOG_INTERVAL_MS = 30_000L

        @Volatile
        var isRunning = false
            private set

        /** True when all traffic of the blocked apps goes into the tunnel and is dropped. */
        @Volatile
        var firewallMode = false
            private set

        fun start(ctx: Context) = send(ctx, ACTION_START)

        fun stop(ctx: Context) = send(ctx, ACTION_STOP)

        /** Re-reads the domain lists without rebuilding the tunnel. */
        fun reload(ctx: Context) {
            if (isRunning) send(ctx, ACTION_RELOAD)
        }

        private fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, TelemetryVpnService::class.java).setAction(action))
        }
    }

    private lateinit var executor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())
    private val uidToPackage = ConcurrentHashMap<Int, String>()
    private val lastDropLogged = ConcurrentHashMap<String, Long>()

    @Volatile
    private var blocklist = Blocklist(emptySet(), emptySet())

    @Volatile
    private var selectedApps: List<String> = emptyList()

    @Volatile
    private var blockAllUids: Set<Int> = emptySet()

    private var reader: TunReader? = null

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newFixedThreadPool(8)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> blocklist = Blocklist.load(this)
            // ACTION_START, or a restart by the system (null intent)
            else -> if (!startTunnel()) {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over, or the user revoked the permission.
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        executor.shutdownNow()
        super.onDestroy()
    }

    /** (Re)builds the tunnel with the current app selection. Calling it again swaps tunnels. */
    @Synchronized
    private fun startTunnel(): Boolean {
        val blockAllApps = Prefs.blockAllPackages(this).filter { it != packageName && isInstalled(it) }
        val telemetryApps = Prefs.selectedPackages(this)
            .filter { it != packageName && it !in blockAllApps && isInstalled(it) }
        val apps = telemetryApps + blockAllApps
        if (apps.isEmpty()) return false
        val firewall = telemetryApps.isEmpty()
        blocklist = Blocklist.load(this)

        val configureIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setConfigureIntent(configureIntent)
            .setMtu(MTU)
            .setBlocking(false)
            .setMetered(false)
            .addAddress(TUN_ADDR4, 32)
            .addAddress(TUN_ADDR6, 128)
            .addDnsServer(FAKE_DNS4)
            .addDnsServer(FAKE_DNS6)
        if (firewall) {
            builder.addRoute("0.0.0.0", 0).addRoute("::", 0)
        } else {
            builder.addRoute(FAKE_DNS4, 32).addRoute(FAKE_DNS6, 128)
        }
        for (app in apps) builder.addAllowedApplication(app)

        val tun = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            null
        } ?: return false

        reader?.shutdown()
        selectedApps = apps
        blockAllUids = blockAllApps.mapNotNull { uidOf(it) }.toSet()
        uidToPackage.clear()
        lastDropLogged.clear()
        reader = TunReader(tun).also { it.start() }
        firewallMode = firewall
        isRunning = true
        return true
    }

    @Synchronized
    private fun stopTunnel() {
        reader?.shutdown()
        reader = null
        isRunning = false
        firewallMode = false
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun uidOf(pkg: String): Int? = try {
        packageManager.getPackageUid(pkg, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun handleQuery(packet: UdpPacket, tun: TunReader) {
        val uid = ownerUid(OsConstants.IPPROTO_UDP, packet.src, packet.srcPort, packet.dst, packet.dstPort)
        // An unknown sender can only be a blocked app when no app is in telemetry mode.
        val blockAll = uid in blockAllUids || (uid < 0 && firewallMode)
        val query = Dns.parseQuery(packet.payload)
        if (query == null) {
            if (!blockAll) forward(packet, tun)
            return
        }
        val app = packageForUid(uid)
        if (blockAll || blocklist.isBlocked(query.name)) {
            QueryLog.add(app, query.name, blocked = true)
            tun.write(Packets.buildUdpResponse(packet, Dns.blockedResponse(packet.payload, query)))
        } else {
            QueryLog.add(app, query.name, blocked = false)
            forward(packet, tun)
        }
    }

    /** Logs a dropped connection, at most once per socket and destination every 30 seconds. */
    private fun logDropped(flow: Flow) {
        val tcp = flow.protocol == Packets.PROTO_TCP
        val target = "${flow.dst.hostAddress}:${flow.dstPort} (${if (tcp) "TCP" else "UDP"})"
        val now = System.currentTimeMillis()
        val key = "${flow.srcPort}|$target"
        val last = lastDropLogged[key]
        if (last != null && now - last < DROP_LOG_INTERVAL_MS) return
        if (lastDropLogged.size > 5000) lastDropLogged.clear()
        lastDropLogged[key] = now
        val protocol = if (tcp) OsConstants.IPPROTO_TCP else OsConstants.IPPROTO_UDP
        val uid = ownerUid(protocol, flow.src, flow.srcPort, flow.dst, flow.dstPort)
        QueryLog.add(packageForUid(uid), target, blocked = true, connection = true)
    }

    /** UID of the app owning a connection (the system resolver tags its DNS sockets with the app's UID). */
    private fun ownerUid(protocol: Int, src: InetAddress, srcPort: Int, dst: InetAddress, dstPort: Int): Int = try {
        getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(
            protocol,
            InetSocketAddress(src, srcPort),
            InetSocketAddress(dst, dstPort),
        )
    } catch (e: Exception) {
        Process.INVALID_UID
    }

    private fun packageForUid(uid: Int): String? {
        val fallback = selectedApps.singleOrNull()
        if (uid < 0) return fallback
        uidToPackage[uid]?.let { return it }
        val packages = packageManager.getPackagesForUid(uid) ?: return fallback
        val name = packages.firstOrNull { it in selectedApps } ?: packages.firstOrNull() ?: return fallback
        uidToPackage[uid] = name
        return name
    }

    private fun forward(packet: UdpPacket, tun: TunReader) {
        try {
            DatagramSocket().use { socket ->
                protect(socket)
                socket.soTimeout = UPSTREAM_TIMEOUT_MS
                val buf = ByteArray(MAX_DNS_SIZE)
                for (server in upstreamServers()) {
                    try {
                        socket.send(DatagramPacket(packet.payload, packet.payload.size, InetSocketAddress(server, 53)))
                        val response = DatagramPacket(buf, buf.size)
                        socket.receive(response)
                        tun.write(Packets.buildUdpResponse(packet, buf.copyOf(response.length)))
                        return
                    } catch (e: IOException) {
                        // try the next server
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "DNS forward failed", e)
        }
    }

    /** DNS servers of the real network (this app is outside the tunnel), then public fallbacks. */
    private fun upstreamServers(): List<InetAddress> {
        val cm = getSystemService(ConnectivityManager::class.java)
        val system = try {
            cm.activeNetwork?.let { cm.getLinkProperties(it)?.dnsServers } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val fake = setOf(InetAddress.getByName(FAKE_DNS4), InetAddress.getByName(FAKE_DNS6))
        return (system.filter { it !in fake } + FALLBACK_DNS.map { InetAddress.getByName(it) }).distinct()
    }

    /** Reads packets from the tunnel. Non-blocking + poll so that shutdown() is noticed quickly. */
    private inner class TunReader(private val tun: ParcelFileDescriptor) : Thread("tun-reader") {
        @Volatile
        private var stopped = false
        private val out = FileOutputStream(tun.fileDescriptor)

        fun shutdown() {
            stopped = true
        }

        fun write(packet: ByteArray) {
            if (stopped) return
            synchronized(out) {
                try {
                    out.write(packet)
                } catch (e: IOException) {
                    Log.w(TAG, "tun write failed", e)
                }
            }
        }

        override fun run() {
            val tunFd = tun.fileDescriptor
            val buf = ByteArray(MTU)
            val pollFd = StructPollfd()
            pollFd.fd = tunFd
            pollFd.events = OsConstants.POLLIN.toShort()
            try {
                while (!stopped) {
                    pollFd.revents = 0
                    val ready = try {
                        Os.poll(arrayOf(pollFd), 500)
                    } catch (e: ErrnoException) {
                        if (e.errno == OsConstants.EINTR) continue else throw e
                    }
                    if (stopped) break
                    if (ready == 0) continue
                    val events = pollFd.revents.toInt()
                    if ((events and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL)) != 0) break
                    val len = try {
                        Os.read(tunFd, buf, 0, buf.size)
                    } catch (e: ErrnoException) {
                        if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue else throw e
                    }
                    if (len <= 0) continue
                    val packet = Packets.parseUdp(buf, len)
                    if (packet != null && packet.dstPort == 53) {
                        executor.execute { handleQuery(packet, this@TunReader) }
                        continue
                    }
                    // Anything else only reaches the tunnel in firewall mode, where every app
                    // in it is blocked: drop it, logging the first packet of each connection.
                    val flow = Packets.parseFlow(buf, len) ?: continue
                    if (flow.tcpSyn || flow.protocol == Packets.PROTO_UDP) {
                        executor.execute { logDropped(flow) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "tunnel reader stopped", e)
            } finally {
                try {
                    tun.close()
                } catch (e: IOException) {
                    // ignore
                }
                if (!stopped) {
                    // The tunnel died on its own: reflect it in the UI.
                    mainHandler.post {
                        if (reader === this@TunReader) {
                            stopTunnel()
                            stopSelf()
                        }
                    }
                }
            }
        }
    }
}
