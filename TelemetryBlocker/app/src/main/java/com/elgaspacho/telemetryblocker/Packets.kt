package com.elgaspacho.telemetryblocker

import java.net.InetAddress

/** A UDP datagram extracted from an IPv4 or IPv6 packet read from the tunnel. */
class UdpPacket(
    val ipVersion: Int,
    val src: InetAddress,
    val dst: InetAddress,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
)

/** A TCP or UDP packet that is not a DNS query (only seen when all traffic is routed to the tunnel). */
class Flow(
    val protocol: Int,
    val src: InetAddress,
    val dst: InetAddress,
    val srcPort: Int,
    val dstPort: Int,
    val tcpSyn: Boolean,
)

/** Minimal IPv4/IPv6 + UDP parsing and building, just enough to answer DNS queries. */
object Packets {
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    private class IpHeader(val version: Int, val length: Int, val protocol: Int, val src: ByteArray, val dst: ByteArray)

    private fun parseIp(buf: ByteArray, len: Int): IpHeader? {
        if (len < 1) return null
        return when ((buf[0].toInt() shr 4) and 0xF) {
            4 -> if (len < 20) null else IpHeader(
                version = 4,
                length = (buf[0].toInt() and 0xF) * 4,
                protocol = buf[9].toInt() and 0xFF,
                src = buf.copyOfRange(12, 16),
                dst = buf.copyOfRange(16, 20),
            )
            // Extension headers are not handled: the system resolver never sends them.
            6 -> if (len < 40) null else IpHeader(
                version = 6,
                length = 40,
                protocol = buf[6].toInt() and 0xFF,
                src = buf.copyOfRange(8, 24),
                dst = buf.copyOfRange(24, 40),
            )
            else -> null
        }
    }

    fun parseUdp(buf: ByteArray, len: Int): UdpPacket? {
        val ip = parseIp(buf, len) ?: return null
        val headerLen = ip.length
        if (ip.protocol != PROTO_UDP || len < headerLen + 8) return null
        val udpLen = u16(buf, headerLen + 4)
        val end = minOf(len, headerLen + udpLen)
        if (end < headerLen + 8) return null
        return UdpPacket(
            ipVersion = ip.version,
            src = InetAddress.getByAddress(ip.src),
            dst = InetAddress.getByAddress(ip.dst),
            srcPort = u16(buf, headerLen),
            dstPort = u16(buf, headerLen + 2),
            payload = buf.copyOfRange(headerLen + 8, end),
        )
    }

    /** The TCP or UDP connection a packet belongs to; null for other protocols (ICMP...). */
    fun parseFlow(buf: ByteArray, len: Int): Flow? {
        val ip = parseIp(buf, len) ?: return null
        val headerLen = ip.length
        if ((ip.protocol != PROTO_TCP && ip.protocol != PROTO_UDP) || len < headerLen + 4) return null
        // SYN without ACK: the first packet of a new TCP connection.
        val tcpSyn = ip.protocol == PROTO_TCP && len >= headerLen + 14 &&
            (buf[headerLen + 13].toInt() and 0x12) == 0x02
        return Flow(
            protocol = ip.protocol,
            src = InetAddress.getByAddress(ip.src),
            dst = InetAddress.getByAddress(ip.dst),
            srcPort = u16(buf, headerLen),
            dstPort = u16(buf, headerLen + 2),
            tcpSyn = tcpSyn,
        )
    }

    /** Builds the reply to [request]: addresses and ports swapped, carrying [payload]. */
    fun buildUdpResponse(request: UdpPacket, payload: ByteArray): ByteArray {
        val srcAddr = request.dst.address
        val dstAddr = request.src.address
        val udpLen = 8 + payload.size
        return if (request.ipVersion == 4) {
            val p = ByteArray(20 + udpLen)
            p[0] = 0x45.toByte()
            put16(p, 2, p.size)
            put16(p, 6, 0x4000) // don't fragment
            p[8] = 64 // TTL
            p[9] = PROTO_UDP.toByte()
            System.arraycopy(srcAddr, 0, p, 12, 4)
            System.arraycopy(dstAddr, 0, p, 16, 4)
            put16(p, 10, fold(sum(p, 0, 20)))
            put16(p, 20, request.dstPort)
            put16(p, 22, request.srcPort)
            put16(p, 24, udpLen)
            // UDP checksum is optional over IPv4: left at 0.
            System.arraycopy(payload, 0, p, 28, payload.size)
            p
        } else {
            val p = ByteArray(40 + udpLen)
            p[0] = 0x60.toByte()
            put16(p, 4, udpLen)
            p[6] = PROTO_UDP.toByte()
            p[7] = 64 // hop limit
            System.arraycopy(srcAddr, 0, p, 8, 16)
            System.arraycopy(dstAddr, 0, p, 24, 16)
            put16(p, 40, request.dstPort)
            put16(p, 42, request.srcPort)
            put16(p, 44, udpLen)
            System.arraycopy(payload, 0, p, 48, payload.size)
            // UDP checksum is mandatory over IPv6: pseudo-header + UDP header + data.
            val total = sum(p, 8, 32) + udpLen + PROTO_UDP + sum(p, 40, udpLen)
            val checksum = fold(total)
            put16(p, 46, if (checksum == 0) 0xFFFF else checksum)
            p
        }
    }

    fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    fun put16(b: ByteArray, off: Int, value: Int) {
        b[off] = (value shr 8).toByte()
        b[off + 1] = value.toByte()
    }

    private fun sum(b: ByteArray, off: Int, len: Int): Long {
        var s = 0L
        var i = 0
        while (i + 1 < len) {
            s += u16(b, off + i)
            i += 2
        }
        if (i < len) s += (b[off + i].toInt() and 0xFF) shl 8
        return s
    }

    private fun fold(sum: Long): Int {
        var s = sum
        while ((s shr 16) != 0L) s = (s and 0xFFFF) + (s shr 16)
        return s.inv().toInt() and 0xFFFF
    }
}
