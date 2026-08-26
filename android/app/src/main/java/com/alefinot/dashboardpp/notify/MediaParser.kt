package com.alefinot.dashboardpp.notify

import android.service.notification.StatusBarNotification

/**
 * The §6.3 media gate — the `package_name` heuristic. A notification is
 * *media* when its package matches a known media player (Spotify, YouTube
 * Music, YouTube, the stock Music app). The ESP `barSetSong` then shows the
 * song in the bar (only while it is playing; the bar's `BAR_TIMEOUT_MS` is
 * conditional on the song being active, so alerts get their 7 s timeout
 * only then).
 */
object MediaParser {
    private val MEDIA_PACKAGES = setOf(
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.google.android.youtube",
        "com.android.music",
    )

    fun isMedia(n: StatusBarNotification): Boolean {
        val pkg = n.packageName?.lowercase() ?: return false
        return MEDIA_PACKAGES.any { pkg.contains(it) }
    }

    /**
     * Parse a media notification into a [MediaDto] (§4.3 MEDIA).
     *
     * `active` is the play-state extra `android.media.PLAYBACK_STATE`,
     * which is an INTEGER (MediaPlayer.PLAY_STATE_*: 0=NONE 1=PAUSED
     * 2=PLAYING 3=BUFFERING 4=ERROR ...), not a Boolean - reading it with
     * getBoolean always returned the default. Only PLAYING/BUFFERING
     * keep the bar's song state on; PAUSED/stop clear it. When the extras
     * bundle carries no state at all (some Android versions strip media
     * extras), assume playing - the previous behavior for an unknown
     * state.
     */
    fun parse(n: StatusBarNotification): MediaDto {
        val title = n.extras.getCharSequence("title")?.toString()
            ?: n.extras.getCharSequence("android.title")?.toString()
            ?: ""
        val song = n.extras.getCharSequence("text")?.toString()
            ?: n.extras.getCharSequence("bigText")?.toString()
            ?: ""
        val pkg = n.packageName ?: ""
        val icon = AppMonogram.forPackage(pkg)
        val state = n.extras.getInt("android.media.PLAYBACK_STATE", 2)
        val active = state == 2 || state == 3
        return MediaDto(
            artist = title,
            song = song,
            icon = icon,
            active = active,
        )
    }
}
