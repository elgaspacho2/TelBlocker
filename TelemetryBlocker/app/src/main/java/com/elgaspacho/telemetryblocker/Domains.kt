package com.elgaspacho.telemetryblocker

import android.content.Context

object Domains {
    private val WHITESPACE = Regex("\\s+")

    /**
     * Turns a user-typed line into a bare domain. Accepts "example.com", "*.example.com",
     * URLs and hosts-file lines ("0.0.0.0 example.com"). Returns null for comments and junk.
     */
    fun normalize(raw: String): String? {
        var d = raw.substringBefore('#').trim().lowercase()
        if (d.isEmpty()) return null
        d = d.split(WHITESPACE).last()
        d = d.substringAfter("://").substringBefore('/').substringBefore(':')
        d = d.trimStart('*', '.').trimEnd('.')
        if (!d.contains('.')) return null
        if (!d.all { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' }) return null
        return d
    }

    /** "a.b.example.com" -> "example.com"; null when the domain already has only two labels. */
    fun parentDomain(domain: String): String? {
        val labels = domain.split('.')
        return if (labels.size > 2) labels.takeLast(2).joinToString(".") else null
    }

    fun parseLines(text: String): Set<String> =
        text.lineSequence().mapNotNull { normalize(it) }.toSortedSet()
}

/** Blocks a domain when it or one of its parents is listed, unless it or a parent is an exception. */
class Blocklist(private val blocked: Set<String>, private val allowed: Set<String>) {

    fun isBlocked(domain: String): Boolean {
        val suffixes = suffixes(domain)
        if (suffixes.any { it in allowed }) return false
        return suffixes.any { it in blocked }
    }

    private fun suffixes(domain: String): Sequence<String> =
        generateSequence(domain) { current ->
            val dot = current.indexOf('.')
            if (dot < 0) null else current.substring(dot + 1)
        }

    companion object {
        private const val BUILT_IN_ASSET = "telemetry_domains.txt"

        fun builtIn(ctx: Context): List<String> =
            ctx.assets.open(BUILT_IN_ASSET).bufferedReader().useLines { lines ->
                lines.mapNotNull { Domains.normalize(it) }.toList()
            }

        fun load(ctx: Context): Blocklist =
            Blocklist(builtIn(ctx).toHashSet() + Prefs.customBlocked(ctx), Prefs.customAllowed(ctx))
    }
}
