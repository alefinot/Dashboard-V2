package com.alefinot.dashboardpp.ui.components

import java.util.Locale

/**
 * Shared number and duration formatting for the native screens. Fixed to
 * Locale.US: these strings appear in trip tables and must not change shape with
 * the phone's locale (a comma decimal would also stop matching the CSV, which
 * is deliberately written with dots).
 */
fun hudDuration(ms: Long): String {
    val total = ms / 1000
    return String.format(Locale.US, "%d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
}

fun hudBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f kB", bytes / 1024.0)
    else -> "$bytes B"
}

fun hudNum(value: Double?, places: Int = 1): String =
    if (value == null) "—" else String.format(Locale.US, "%.${places}f", value)
