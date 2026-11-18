package com.cesmotacon.tvhome

import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * Hooks for editions that add their own features. This plain edition adds nothing.
 * The Home Assistant edition (github.com/CESMOtacon/tv-home-ha) replaces this one file to add its rows,
 * status pills, doorbell pop-up and weather-station data, so the rest of the code stays the same in both.
 */
object Edition {
    const val NAME = "TV Home"

    /** The app (or its background helper) is starting. Called more than once; make it idempotent. */
    fun init(ctx: Context) {}

    /** Where the weather comes from. */
    fun weather(): WeatherSource = OpenMeteo

    /** Once, while the home screen is built: add rows to [MainActivity.rowsBox] and pills via [MainActivity.addChip]. */
    fun buildHome(home: MainActivity) {}

    fun onHomeResume(home: MainActivity) {}
    fun onHomePause(home: MainActivity) {}

    /** Extra parts for the ambient screen's line (after the date, temperature and weather). */
    fun ambientExtras(): List<String> = emptyList()

    /** Colours (0xRRGGBB) for the "Glow" background to follow (e.g. a running light show), or null to drift through its own. */
    fun glowColors(): IntArray? = null

    /** More apps whose playback shouldn't count as music (no album art background). */
    fun notMusic(): Set<String> = emptySet()

    /** The background helper (an accessibility service) connected or stopped. */
    fun onHelperConnected(svc: HomeKeyService) {}
    fun onHelperDestroyed(svc: HomeKeyService) {}

    /** Remote keys while the helper runs. Return true if handled (e.g. a pop-up has focus). */
    fun onHelperKey(event: KeyEvent): Boolean = false

    /** adb test hooks this edition adds. Return true if handled. */
    fun onDebug(ctx: Context, i: Intent): Boolean = false
}
