package com.elgaspacho.telemetryblocker

/** In-memory record of the DNS queries seen by the tunnel, shown in the log screen. */
object QueryLog {
    class Entry(val time: Long, val pkg: String?, val domain: String, val blocked: Boolean)

    private const val MAX_ENTRIES = 1000
    private val entries = ArrayDeque<Entry>()
    private val blockedCounts = HashMap<String, Int>()

    @Synchronized
    fun add(pkg: String?, domain: String, blocked: Boolean) {
        entries.addLast(Entry(System.currentTimeMillis(), pkg, domain, blocked))
        if (entries.size > MAX_ENTRIES) entries.removeFirst()
        if (blocked && pkg != null) blockedCounts[pkg] = (blockedCounts[pkg] ?: 0) + 1
    }

    /** Newest first. */
    @Synchronized
    fun snapshot(): List<Entry> = entries.reversed()

    @Synchronized
    fun blockedCount(pkg: String): Int = blockedCounts[pkg] ?: 0

    @Synchronized
    fun clear() {
        entries.clear()
        blockedCounts.clear()
    }
}
