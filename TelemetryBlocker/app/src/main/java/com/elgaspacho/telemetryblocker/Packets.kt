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

/** Minimal IPv4/IPv6 + UDP parsing and building, just enough to answer DNS queries. */
object Packets {
    private const val PROTO_UDP = 17

    fun parseUdp(buf: ByteArray, len: Int): UdpPacket? {
        if (len < 1) return null
        val version = (buf[0].toInt() shr 4) and 0xF
        val headerLen: Int
        val proto: Int
        val src: ByteArray
        val dst: ByteArray
        when (version) {
            4 -> {
                if (len < 20) return null
                headerLen = (buf[0].toInt() and 0xF) * 4
                proto = buf[9].toInt() and 0xFF
                src = buf.copyOfRange(12, 16)
                dst = buf.copyOfRange(16, 20)
            }
            6 -> {
                if (len < 40) return null
                // Extension headers are not handled: DNS queries from the system resolver never carry them.
                headerLen = 40
                proto = buf[6].toInt() and 0xFF
                src = buf.copyOfRange(8, 24)
                dst = buf.copyOfRange(24, 40)
            }
            else -> return null
        }
        if (proto != PROTO_UDP || len < headerLen + 8) return null
        val udpLen = u16(buf, headerLen + 4)
        val end = minOf(len, headerLen + udpLen)
        if (end < headerLen + 8) return null
        return UdpPacket(
            ipVersion = version,
            src = InetAddress.getByAddress(src),
            dst = InetAddress.getByAddress(dst),
            srcPort = u16(buf, headerLen),
            dstPort = u16(buf, headerLen + 2),
            payload = buf.copyOfRange(headerLen + 8, end),
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
