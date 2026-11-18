package com.cesmotacon.tvhome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Setup and test hooks, callable only from a computer over adb (the receiver requires the DUMP permission,
 * which only the adb shell and the system hold). Examples, all with  -p com.cesmotacon.tvhome :
 *   adb shell am broadcast -a com.cesmotacon.tvhome.LOCATION -p com.cesmotacon.tvhome --ef lat 40.71 --ef lon -74.01 --es name "New York"
 *   adb shell am broadcast -a com.cesmotacon.tvhome.LOCATION -p com.cesmotacon.tvhome --ez auto true     (back to automatic)
 *   adb shell am broadcast -a com.cesmotacon.tvhome.FAVORITES -p com.cesmotacon.tvhome --es apps "com.netflix.ninja,com.google.android.youtube.tv"
 *   adb shell am broadcast -a com.cesmotacon.tvhome.SEASON -p com.cesmotacon.tvhome --es theme snow     (leaves|snow|fireworks|hearts|none|auto)
 *   adb shell am broadcast -a com.cesmotacon.tvhome.AMBIENT -p com.cesmotacon.tvhome
 *   adb shell am broadcast -a com.cesmotacon.tvhome.SKY -p com.cesmotacon.tvhome --ef rain 0.2 --es name test   (see SkyDebug)
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.action) {
            "com.cesmotacon.tvhome.LOCATION" -> {
                if (i.getBooleanExtra("auto", false)) Place.clear(ctx)
                else {
                    val lat = i.getFloatExtra("lat", Float.NaN); val lon = i.getFloatExtra("lon", Float.NaN)
                    if (lat.isNaN() || lon.isNaN() || lat !in -90f..90f || lon !in -180f..180f) { Log.w("TvHome", "LOCATION needs --ef lat and --ef lon"); return }
                    Place.set(ctx, Place.P(lat.toDouble(), lon.toDouble(), i.getStringExtra("name") ?: "", "manual", System.currentTimeMillis()))
                }
                Weather.source.refresh(ctx)
                Log.i("TvHome", "location now ${Place.current}")
            }
            "com.cesmotacon.tvhome.FAVORITES" -> {
                val apps = i.getStringExtra("apps")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return
                Apps.setFavorites(ctx, apps)
                MainActivity.instance?.refreshApps(force = true)
            }
            "com.cesmotacon.tvhome.SEASON" -> {
                val th = i.getStringExtra("theme")?.takeIf { it != "auto" }
                ctx.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putString("season", th).apply()
                MainActivity.instance?.applySeasonOverride()
            }
            "com.cesmotacon.tvhome.AMBIENT" -> MainActivity.instance?.enterAmbient()
            "com.cesmotacon.tvhome.SKY" -> {
                val pr = goAsync()
                Thread { try { SkyDebug.render(ctx, i) } catch (e: Exception) { Log.w("TvHome.Sky", "render failed", e) } finally { pr.finish() } }.start()
            }
            else -> if (!Edition.onDebug(ctx, i)) Log.w("TvHome", "unknown action ${i.action}")
        }
    }
}
