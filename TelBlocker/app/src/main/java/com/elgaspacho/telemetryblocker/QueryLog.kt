package com.elgaspacho.telemetryblocker

/**
 * In-memory record of what the tunnel saw, shown in the log screen: DNS queries, and the
 * connections dropped for apps in "block everything" mode ([Entry.connection], where
 * [Entry.domain] holds "address:port").
 */
object QueryLog {
    class Entry(val time: Long, val pkg: String?, val domain: String, val blocked: Boolean, val connection: Boolean)

    private const val MAX_ENTRIES = 1000
    private val entries = ArrayDeque<Entry>()
    private val blockedCounts = HashMap<String, Int>()

    @Synchronized
    fun add(pkg: String?, domain: String, blocked: Boolean, connection: Boolean = false) {
        entries.addLast(Entry(System.currentTimeMillis(), pkg, domain, blocked, connection))
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
