package com.cesmotacon.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real pictures for the Live weather sky, cached on the TV and only fetched while that background is showing:
 *  - the sun as NASA's Solar Dynamics Observatory sees it right now (visible light, today's sunspots), ~50 KB every 20 min
 *  - the moon as it looks this hour, from NASA's Dial-a-Moon (correct phase and tilt), ~100 KB once an hour
 */
object SkyAssets {
    private const val TAG = "TvHome.Sky"
    private const val SUN_URL = "https://sdo.gsfc.nasa.gov/assets/img/latest/latest_512_HMIIC.jpg"
    private const val MOON_API = "https://svs.gsfc.nasa.gov/api/dialamoon/"
    private const val SUN_EVERY = 20 * 60_000L

    class Sun(val disc: Bitmap, val updatedMs: Long)
    /** [img] is NASA's whole square frame (black around the moon); the moon spans [discFrac] of its width. */
    class Moon(val img: Bitmap, val discFrac: Float, val ra: Double, val dec: Double, val phase: Float, val hourMs: Long)

    @Volatile var sun: Sun? = null
        private set
    @Volatile var moon: Moon? = null
        private set
    private var sunFetched = 0L
    private var sunTry = 0L
    private var moonTry = 0L
    private val busy = AtomicBoolean(false)

    private fun dir(ctx: Context) = File(ctx.filesDir, "sky").apply { mkdirs() }

    /** Loads the cache, then fetches whatever is stale. Cheap to call every minute. Returns true if a picture changed. */
    suspend fun refresh(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        if (!busy.compareAndSet(false, true)) return@withContext false
        try {
            val d = dir(ctx)
            var changed = false
            if (sun == null) runCatching { loadSun(d) }.getOrNull()?.let { sun = it; changed = true }
            if (moon == null) runCatching { loadMoon(d) }.getOrNull()?.let { moon = it; changed = true }
            val now = System.currentTimeMillis()
            if (now - sunFetched > SUN_EVERY && now - sunTry > 5 * 60_000L) {
                sunTry = now
                runCatching { fetchSun(d) }.onFailure { Log.w(TAG, "sun: ${it.message}") }.getOrNull()?.let { sun = it; changed = true }
            }
            val hour = now / 3600_000L * 3600_000L
            if (moon?.hourMs != hour && now - moonTry > 10 * 60_000L) {
                moonTry = now
                runCatching { fetchMoon(d, hour) }.onFailure { Log.w(TAG, "moon: ${it.message}") }.getOrNull()?.let { moon = it; changed = true }
            }
            changed
        } finally { busy.set(false) }
    }

    private fun get(url: String) = Net.http.newCall(Net.request(url)).execute()

    // ---------------------------------------------------------------- sun

    private fun fetchSun(d: File): Sun? {
        get(SUN_URL).use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            val bytes = r.body!!.bytes()
            val updated = r.headers.getDate("Last-Modified")?.time ?: System.currentTimeMillis()
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val disc = discOf(src)
            File(d, "sun.png").outputStream().use { disc.compress(Bitmap.CompressFormat.PNG, 100, it) }
            sunFetched = System.currentTimeMillis()
            File(d, "sun.json").writeText(JSONObject().put("updated", updated).put("fetched", sunFetched).toString())
            return Sun(disc, updated)
        }
    }

    private fun loadSun(d: File): Sun? {
        val meta = JSONObject(File(d, "sun.json").readText())
        val bmp = BitmapFactory.decodeFile(File(d, "sun.png").path) ?: return null
        sunFetched = meta.optLong("fetched")
        return Sun(bmp, meta.optLong("updated"))
    }

    /** Cuts the solar disc out of SDO's black square (its label sits in the corner, outside the disc). */
    private fun discOf(src: Bitmap): Bitmap {
        val cx = src.width / 2f; val cy = src.height / 2
        var x0 = 0
        while (x0 < src.width / 2) {
            val p = src.getPixel(x0, cy)
            if (Color.red(p) + Color.green(p) + Color.blue(p) > 120) break
            x0++
        }
        val r = (cx - x0).coerceAtLeast(src.width / 4f) * 0.992f
        val size = 320
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply { setRectToRect(RectF(cx - r, cy - r, cx + r, cy + r), RectF(0f, 0f, size.toFloat(), size.toFloat()), Matrix.ScaleToFit.FILL) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) } }
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, p)
        return out
    }

    // ---------------------------------------------------------------- moon

    private val HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00").withZone(ZoneOffset.UTC)

    private fun fetchMoon(d: File, hour: Long): Moon? {
        val j = get(MOON_API + HOUR.format(Instant.ofEpochMilli(hour))).use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            JSONObject(r.body!!.string())
        }
        val url = j.getJSONObject("image").getString("url").replaceFirst("http:", "https:")
        val src = get(url).use { r ->
            if (!r.isSuccessful) throw IllegalStateException("image HTTP ${r.code}")
            r.body!!.bytes().let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } ?: return null
        val img = Bitmap.createScaledBitmap(src, 360, 360, true)
        File(d, "moon.jpg").outputStream().use { img.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        // the frame is ~2090 arcsec wide; "diameter" is the moon's size in arcsec at that moment
        val frac = (j.optDouble("diameter", 1900.0) / 2092.0).toFloat().coerceIn(0.8f, 1f)
        val meta = JSONObject().put("frac", frac.toDouble()).put("ra", j.getDouble("j2000_ra")).put("dec", j.getDouble("j2000_dec"))
            .put("phase", j.optDouble("phase", 50.0)).put("hour", hour)
        File(d, "moon.json").writeText(meta.toString())
        return Moon(img, frac, meta.getDouble("ra"), meta.getDouble("dec"), meta.getDouble("phase").toFloat(), hour)
    }

    private fun loadMoon(d: File): Moon? {
        val m = JSONObject(File(d, "moon.json").readText())
        val bmp = BitmapFactory.decodeFile(File(d, "moon.jpg").path) ?: return null
        return Moon(bmp, m.getDouble("frac").toFloat(), m.getDouble("ra"), m.getDouble("dec"), m.getDouble("phase").toFloat(), m.getLong("hour"))
    }
}
