package com.elgaspacho.telemetryblocker

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "settings"
    private const val KEY_APPS = "selected_packages"
    private const val KEY_BLOCKED = "custom_blocked"
    private const val KEY_ALLOWED = "custom_allowed"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun getSet(ctx: Context, key: String): Set<String> =
        prefs(ctx).getStringSet(key, null)?.toSet() ?: emptySet()

    private fun putSet(ctx: Context, key: String, value: Set<String>) =
        prefs(ctx).edit().putStringSet(key, HashSet(value)).apply()

    fun selectedPackages(ctx: Context) = getSet(ctx, KEY_APPS)
    fun setSelectedPackages(ctx: Context, value: Set<String>) = putSet(ctx, KEY_APPS, value)

    fun customBlocked(ctx: Context) = getSet(ctx, KEY_BLOCKED)
    fun setCustomBlocked(ctx: Context, value: Set<String>) = putSet(ctx, KEY_BLOCKED, value)

    fun customAllowed(ctx: Context) = getSet(ctx, KEY_ALLOWED)
    fun setCustomAllowed(ctx: Context, value: Set<String>) = putSet(ctx, KEY_ALLOWED, value)

    fun addBlocked(ctx: Context, domain: String) {
        setCustomBlocked(ctx, customBlocked(ctx) + domain)
        setCustomAllowed(ctx, customAllowed(ctx) - domain)
    }

    fun addAllowed(ctx: Context, domain: String) {
        setCustomAllowed(ctx, customAllowed(ctx) + domain)
        setCustomBlocked(ctx, customBlocked(ctx) - domain)
    }
}
