package com.elgaspacho.telemetryblocker

import android.content.Context
import android.content.pm.PackageManager

/** The Google system components targeted by the "cut Google services" switch. */
object GoogleServices {
    val PACKAGES = listOf(
        "com.google.android.gms", // Google Play Services
        "com.google.android.gsf", // Google Services Framework
        "com.android.vending", // Google Play Store
    )

    fun installed(ctx: Context): List<String> = PACKAGES.filter {
        try {
            ctx.packageManager.getApplicationInfo(it, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
