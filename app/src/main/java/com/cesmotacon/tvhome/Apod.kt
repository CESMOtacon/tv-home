package com.cesmotacon.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.Html
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * NASA Astronomy Picture of the Day, cached on the TV.
 * APOD moved to science.nasa.gov/apod/ in 2026 and the old API now returns the NASA logo for recent days,
 * so the page is read directly; the API is only a fallback. Logos, thumbnails and video days are skipped.
 */
object Apod {
    private const val TAG = "TvHome.Apod"
    private const val PAGE = "https://science.nasa.gov/apod/"
    private const val API = "https://api.nasa.gov/planetary/apod?api_key=DEMO_KEY&thumbs=true"
    private const val MAX_BYTES = 15L * 1024 * 1024
    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

    class Pic(val date: String, val title: String, val credit: String?, val bitmap: Bitmap)

    private fun dir(ctx: Context) = File(ctx.filesDir, "apod").apply { mkdirs() }

    suspend fun load(ctx: Context, targetW: Int): Pic? = withContext(Dispatchers.IO) {
        val d = dir(ctx)
        val metaFile = File(d, "latest.json")
        val today = LocalDate.now(ZoneId.of("America/New_York")).toString()
        val cached = runCatching { JSONObject(metaFile.readText()) }.getOrNull()
        val cachedOk = cached != null && goodImage(File(d, cached.optString("file")))
        val fresh = cachedOk && (cached!!.optString("checked") == today || System.currentTimeMillis() - cached.optLong("fetched") < 3 * 3600_000L)
        if (!fresh) {
            val ok = runCatching { fromPage(d, metaFile) }.onFailure { Log.w(TAG, "page: ${it.message}") }.getOrDefault(false) ||
                     runCatching { fromApi(d, metaFile) }.onFailure { Log.w(TAG, "api: ${it.message}") }.getOrDefault(false)
            if (!ok && cachedOk) cached!!.put("fetched", System.currentTimeMillis()).let { metaFile.writeText(it.toString()) }
        }
        val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return@withContext null
        val img = File(d, meta.optString("file"))
        if (!goodImage(img)) return@withContext null
        val bmp = decode(img, targetW) ?: return@withContext null
        Pic(meta.optString("file"), meta.optString("title"), meta.optString("copyright").takeIf { it.isNotBlank() }, framed(bmp, targetW))
    }

    private fun get(url: String): String = Net.http.newCall(Net.request(url)).execute().use { r ->
        if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
        r.body!!.string()
    }

    /** Today's picture from the APOD page: the newest apod asset that isn't the annotated copy. */
    private fun fromPage(d: File, metaFile: File): Boolean {
        val html = get(PAGE)
        // The picture shows up as content/dam/... (full size) or dynamicimage/... (resized) links; the newest one wins.
        val rx = Regex("""https://assets\.science\.nasa\.gov/(?:content/dam/science|dynamicimage/assets/science)/cds/apod/apod/(\d{4})/([a-z]+)/([^/"'\s?&]+?\.(?:jpe?g|png|gif))(?=[/?"'\s&])""", RegexOption.IGNORE_CASE)
        val hits = rx.findAll(html).map { Triple(it.groupValues[1].toInt(), MONTHS.indexOf(it.groupValues[2].lowercase()), it) }
            .filter { it.second >= 0 && !it.third.groupValues[3].contains("_annotated", true) }.toList()
        val best = hits.maxWithOrNull(compareBy<Triple<Int, Int, MatchResult>>({ it.first }, { it.second }).thenBy { -it.third.range.first })
            ?: throw IllegalStateException("no picture on the page (a video day?)")
        val (year, _, m) = best
        val name = m.groupValues[3]
        val title = Regex("""<h2 class="display-48[^"]*">(.*?)</h2>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() } ?: "Astronomy Picture of the Day"
        val out = File(d, "page-$year-${m.groupValues[2]}-$name.img")
        if (!goodImage(out)) {
            out.delete()
            val urls = listOf("https://assets.science.nasa.gov/dynamicimage/assets/science/cds/apod/apod/$year/${m.groupValues[2]}/$name?w=1920",
                              "https://assets.science.nasa.gov/content/dam/science/cds/apod/apod/$year/${m.groupValues[2]}/$name/jcr:content/renditions/cq5dam.web.1280.1280.jpeg")
            if (urls.none { u -> runCatching { download(u, out) }.getOrDefault(false) && goodImage(out).also { if (!it) out.delete() } })
                throw IllegalStateException("couldn't download $name")
        }
        save(d, metaFile, JSONObject().put("date", LocalDate.now(ZoneId.of("America/New_York")).toString()).put("title", title), out)
        return true
    }

    /** Old API, newest usable day in the last week. */
    private fun fromApi(d: File, metaFile: File): Boolean {
        val today = LocalDate.now(ZoneId.of("America/New_York"))
        for (back in 0..2) {
            val json = runCatching { JSONObject(get(if (back == 0) API else "$API&date=${today.minusDays(back.toLong())}")) }.getOrNull() ?: continue
            val date = json.optString("date").takeIf { it.isNotBlank() } ?: continue
            val cands = (if (json.optString("media_type") == "image") listOf(json.optString("hdurl"), json.optString("url"))
                         else listOf(json.optString("thumbnail_url"))).filter { it.startsWith("http") }
            if (cands.isEmpty() || cands.any { "nasa-logo" in it } || json.optString("title").equals("NASA Science", true)) continue
            val out = File(d, "api-$date.img")
            if (!goodImage(out)) {
                out.delete()
                if (cands.none { u -> runCatching { download(u, out) }.getOrDefault(false) && goodImage(out).also { if (!it) out.delete() } }) continue
            }
            save(d, metaFile, json, out)
            return true
        }
        return false
    }

    private fun save(d: File, metaFile: File, json: JSONObject, img: File) {
        json.put("file", img.name).put("fetched", System.currentTimeMillis())
            .put("checked", LocalDate.now(ZoneId.of("America/New_York")).toString())
        json.optString("copyright").takeIf { it.isNotBlank() }?.let { json.put("copyright", it.replace("\n", " ").trim()) }
        metaFile.writeText(json.toString())
        d.listFiles { f -> f.name.endsWith(".img") }?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
        Log.i(TAG, "picture of the day: ${json.optString("title")} (${img.name})")
    }

    private fun download(url: String, out: File): Boolean {
        Net.http.newCall(Net.request(url)).execute().use { r ->
            if (!r.isSuccessful) return false
            val body = r.body ?: return false
            if (body.contentLength() > MAX_BYTES) return false
            val tmp = File(out.path + ".part")
            tmp.outputStream().use { o -> body.byteStream().copyTo(o) }
            if (tmp.length() == 0L || tmp.length() > MAX_BYTES) { tmp.delete(); return false }
            return tmp.renameTo(out)
        }
    }

    /** A real photo (logos and thumbnails fail this). */
    private fun goodImage(f: File): Boolean {
        if (!f.exists() || f.length() < 40_000) return false
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return o.outWidth >= 900 && o.outHeight >= 500
    }

    private fun decode(f: File, targetW: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= targetW && o.outHeight / (sample * 2) >= targetW * 9 / 16) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * Wide photos are used as-is (cropped to fill the screen). Tall or square ones get a gallery frame:
     * a blurred, darkened copy fills the screen and the whole photo sits on the right at full height.
     */
    private fun framed(src: Bitmap, targetW: Int): Bitmap {
        val aspect = src.width.toFloat() / src.height
        if (aspect >= 1.3f) return src
        val w = targetW; val h = targetW * 9 / 16
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        // cheap blur: shrink a lot, then stretch back up with filtering
        val tiny = Bitmap.createScaledBitmap(src, 24, (24 / aspect).toInt().coerceAtLeast(8), true)
        val cover = run { val sh = w / aspect; RectF(0f, (h - sh) / 2f, w.toFloat(), (h + sh) / 2f) }
        c.drawBitmap(tiny, null, cover, p)
        c.drawColor(Color.argb(120, 0, 0, 0))
        val ph = h * 0.92f; val pw = ph * aspect
        val left = w - pw - w * 0.06f; val top = (h - ph) / 2f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, 1f, Color.argb(120, 0, 0, 0), Color.argb(120, 0, 0, 0), Shader.TileMode.CLAMP)
            setShadowLayer(w * 0.02f, 0f, w * 0.006f, Color.argb(200, 0, 0, 0))
        }
        c.drawRect(left, top, left + pw, top + ph, shadow)
        c.drawBitmap(src, Rect(0, 0, src.width, src.height), RectF(left, top, left + pw, top + ph), p)
        tiny.recycle()
        return out
    }
}
