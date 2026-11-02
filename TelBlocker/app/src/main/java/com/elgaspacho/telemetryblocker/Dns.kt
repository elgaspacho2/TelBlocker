package com.elgaspacho.telemetryblocker

class DnsQuery(val name: String, val questionEnd: Int)

object Dns {
    /** Returns the first question of a DNS query, or null if the message is not a parsable query. */
    fun parseQuery(msg: ByteArray): DnsQuery? {
        if (msg.size < 12) return null
        if ((Packets.u16(msg, 2) and 0x8000) != 0) return null // this is a response
        if (Packets.u16(msg, 4) < 1) return null
        val name = StringBuilder()
        var pos = 12
        while (true) {
            if (pos >= msg.size) return null
            val labelLen = msg[pos].toInt() and 0xFF
            if (labelLen == 0) {
                pos++
                break
            }
            if ((labelLen and 0xC0) != 0 || pos + 1 + labelLen > msg.size) return null
            if (name.isNotEmpty()) name.append('.')
            for (i in 1..labelLen) name.append((msg[pos + i].toInt() and 0xFF).toChar())
            pos += 1 + labelLen
        }
        if (pos + 4 > msg.size) return null // QTYPE + QCLASS
        return DnsQuery(name.toString().lowercase(), pos + 4)
    }

    /** An NXDOMAIN answer to [query], echoing its ID and question. */
    fun blockedResponse(msg: ByteArray, query: DnsQuery): ByteArray {
        val r = msg.copyOf(query.questionEnd)
        val requestFlags = Packets.u16(msg, 2)
        // QR=1, keep OPCODE and RD, RA=1, RCODE=3 (NXDOMAIN)
        Packets.put16(r, 2, 0x8000 or (requestFlags and 0x7900) or 0x0080 or 3)
        Packets.put16(r, 4, 1)
        Packets.put16(r, 6, 0)
        Packets.put16(r, 8, 0)
        Packets.put16(r, 10, 0)
        return r
    }
}
