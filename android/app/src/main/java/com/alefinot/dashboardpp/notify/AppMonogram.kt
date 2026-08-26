package com.alefinot.dashboardpp.notify

import android.content.pm.PackageManager

/**
 * The bar's monogram: the first letter of the app's *label* (WhatsApp →
 * "W"), resolved via [PackageManager]. The old code took the first char of
 * the *package name* — every `com.*` package (the overwhelming majority of
 * apps) rendered a constant "C". Resolution is cached per package; the
 * fallback (unresolvable / no context yet) is the first letter of the first
 * meaningful package segment (com.whatsapp → "whatsapp" → "W").
 */
object AppMonogram {
    @Volatile
    private var pm: PackageManager? = null

    private val cache = HashMap<String, String>()
    private const val MAX_CACHE = 256

    /** Called once from the notification listener service. */
    fun init(pm: PackageManager) {
        this.pm = pm
    }

    /** The monogram for [pkg]; `""` when a PackageManager is unavailable. */
    fun forPackage(pkg: String): String {
        if (pkg.isEmpty()) return ""
        val p = pm ?: return fallback(pkg)
        return synchronized(cache) {
            val hit = cache[pkg]
            if (hit != null) {
                hit
            } else {
                val letter = try {
                    val label =
                        p.getApplicationLabel(p.getApplicationInfo(pkg, 0)).trim()
                    (label.firstOrNull { it.isLetter() } ?: 'A')
                        .uppercaseChar()
                        .toString()
                } catch (e: Exception) {
                    fallback(pkg)
                }
                if (cache.size >= MAX_CACHE) cache.clear()
                cache[pkg] = letter
                letter
            }
        }
    }

    private fun fallback(pkg: String): String {
        var s = pkg
        while (true) {
            val i = s.indexOf('.')
            if (i <= 0) break
            val head = s.substring(0, i)
            if (head !in setOf("com", "org", "io", "app", "cn", "co", "net")) break
            s = s.substring(i + 1)
        }
        return (s.firstOrNull { it.isLetter() } ?: 'A').uppercaseChar().toString()
    }
}
