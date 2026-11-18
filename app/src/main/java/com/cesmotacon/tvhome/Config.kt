package com.cesmotacon.tvhome

object Config {
    /**
     * A fresh install fills the Apps row with whichever of these are installed, in this order.
     * Change it on the TV: hold OK on an app to move or remove it, and add apps from All apps.
     */
    val DEFAULT_FAVORITES = listOf(
        "com.google.android.youtube.tv",
        "com.netflix.ninja",
        "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus",
        "com.wbd.stream",
        "com.hulu.livingroomplus",
        "com.google.android.youtube.tvunplugged",
        "com.apple.atve.androidtv.appletv",
        "com.cbs.ott",
        "com.peacocktv.peacockandroid",
        "com.plexapp.android",
        "com.spotify.tv.android",
        "tv.twitch.android.app",
        "org.xbmc.kodi",
        "com.valvesoftware.steamlink",
    )

    /** Apps whose playback should NOT take over the background with album art (video, games). */
    val NOT_MUSIC = setOf(
        "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus",
        "com.wbd.stream", "com.hulu.livingroomplus", "com.google.android.youtube.tvunplugged", "com.apple.atve.androidtv.appletv",
        "com.cbs.ott", "com.peacocktv.peacockandroid", "tv.twitch.android.app", "com.valvesoftware.steamlink",
        "com.google.android.videos", "com.google.android.apps.mediashell", "com.google.android.tv",
    )

    /** Google TV's own home screen (the Home button can be sent to TV Home instead). */
    const val STOCK_HOME_PKG = "com.google.android.apps.tv.launcherx"
    const val STOCK_HOME_CLS = "com.google.android.apps.tv.launcherx.home.HomeActivity"
}
