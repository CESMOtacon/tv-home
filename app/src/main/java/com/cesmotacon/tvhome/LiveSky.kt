package com.cesmotacon.tvhome

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LightingColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Live weather scene: looking south (east on the left, west on the right) over rolling hills.
 * Everything it shows comes from [Wx] (see [Weather]); the sun and moon are NASA's live pictures ([SkyAssets]).
 * Drawn at ~30 fps by [AnimatedBg], only while TV Home is on screen.
 */
class LiveSky {
    private var W = 0f
    private var H = 0f
    private var k = 1f
    private val rnd = Random(7)
    private fun r(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    private val skyPaint = Paint()
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val dst = RectF()

    // smoothed state
    private var first = true
    private var t = 0f
    private var elev = 0f
    private var az = 180f
    private var cover = 0f
    private var sunOut = 1f
    private var rain = 0f
    private var snow = 0f
    private var fog = 0f
    private var windV = 0f
    private var slant = 0f
    private var curSlant = 0f
    private var gusty = 0f
    private var moonAlt = -90f
    private var moonAz = 180f
    private var bolt = false

    // scenery
    private class Star(val x: Float, val y: Float, val r: Float, val a: Float, val ph: Float, val tw: Float)
    private var stars: List<Star> = emptyList()
    private class Hill(val ground: Path, val trees: Path, val day: Int, val night: Int)
    private val hills = ArrayList<Hill>()
    private class Cloud(var x: Float, val y: Float, val s: Float, val v: Float, val bmp: Bitmap, val order: Float, var a: Float = 0f)
    private val clouds = ArrayList<Cloud>()
    private var veil: Bitmap? = null
    private var deck: Bitmap? = null
    private var deckX = 0f
    private var fogBmp: Bitmap? = null
    private var fogX = 0f
    private var glow: Bitmap? = null
    private var sunR = 66f
    private var moonR = 54f

    // rain, splashes, snow
    private val maxRain = 900
    private val rx = FloatArray(maxRain); private val ry = FloatArray(maxRain)
    private val rl = FloatArray(maxRain); private val rs = FloatArray(maxRain)
    private val farPts = FloatArray(maxRain * 4); private val nearPts = FloatArray(maxRain * 4)
    private var rainN = 0
    private val maxSplash = 80
    private val spx = FloatArray(maxSplash); private val spy = FloatArray(maxSplash); private val spAge = FloatArray(maxSplash) { 9f }
    private var splashAcc = 0f
    private var splashNext = 0
    private val maxSnow = 420
    private val fx = FloatArray(maxSnow); private val fy = FloatArray(maxSnow)
    private val fr = FloatArray(maxSnow); private val fs = FloatArray(maxSnow); private val fph = FloatArray(maxSnow)
    private var snowN = 0

    // lightning
    private var nextBolt = 3f
    private var flashT = -1f
    private val boltPath = Path()

    fun setup(w: Int, h: Int) {
        W = w.toFloat(); H = h.toFloat(); k = W / 1920f
        sunR = 66f * k; moonR = 54f * k
        stars = List(240) { Star(r(0f, W), r(0f, H * .72f), r(.7f, 2.1f) * k, r(.3f, 1f), r(0f, 6.28f), r(.6f, 2f)) }

        hills.clear()
        val m = LocalDate.now().monthValue
        val dayCols = if (m >= 11 || m <= 3) intArrayOf(0x5A6656, 0x434F3D, 0x2C382B) else intArrayOf(0x4A6E5A, 0x34544A, 0x223A34)
        val nightCols = intArrayOf(0x0E1420, 0x0A0F1A, 0x060A12)
        val specs = arrayOf(floatArrayOf(.70f, 60f), floatArrayOf(.78f, 45f), floatArrayOf(.87f, 30f))
        specs.forEachIndexed { i, sp ->
            val ys = FloatArray(41) { j -> H * sp[0] + (sin(j * .55f + i * 2f) * sp[1] * .6f + sin(j * .21f + i) * sp[1] * .4f) * k }
            val g = Path().apply {
                moveTo(-10f, H); lineTo(-10f, ys[0]); lineTo(0f, ys[0])
                for (j in 0 until 40) {
                    val x0 = j * W / 40f; val x1 = (j + 1) * W / 40f
                    quadTo(x0, ys[j], (x0 + x1) / 2f, (ys[j] + ys[j + 1]) / 2f)
                }
                lineTo(W + 10f, ys[40]); lineTo(W + 10f, H); close()
            }
            val tr = Path()
            if (i > 0) {
                var x = -10f
                while (x < W + 10f) {
                    val front = i == 2
                    val dense = sin(x / (k * (if (front) 170f else 120f)) + i) + sin(x / (k * 61f)) * .5f
                    if (dense > (if (front) .35f else .6f)) pine(tr, x, ridge(ys, x) + 4f * k, (if (front) r(24f, 58f) else r(14f, 30f)) * k)
                    x += (if (front) r(10f, 18f) else r(8f, 14f)) * k
                }
            }
            hills.add(Hill(g, tr, dayCols[i], nightCols[i]))
        }

        clouds.clear()
        repeat(14) { i ->
            val y = r(H * .05f, H * .46f)
            val depth = y / (H * .46f)
            val bw = (r(420f, 640f) * k * .5f).toInt().coerceAtLeast(40)
            val bh = (bw * r(.38f, .5f)).toInt()
            clouds.add(Cloud(r(-.1f * W, 1.1f * W), y, .55f + depth * .75f, .55f + depth * .8f, makeCloud(bw, bh, false), (i + r(0f, 1f)) / 14f))
        }
        clouds.sortBy { it.y }
        veil = makeCloud((560 * k * .5f).toInt().coerceAtLeast(40), (250 * k * .5f).toInt().coerceAtLeast(20), true)
        deck = makeDeck((W / 2).toInt(), (H * .62f / 2).toInt())
        fogBmp = makeFog((W / 2).toInt(), (H * .5f / 2).toInt())
        glow = makeGlow(128)

        for (i in 0 until maxRain) {
            val near = i % 3 == 0
            rx[i] = r(-.2f * W, 1.2f * W); ry[i] = r(-H, H)
            rl[i] = (if (near) r(34f, 52f) else r(16f, 26f)) * k
            rs[i] = (if (near) r(1500f, 1900f) else r(950f, 1150f)) * k
        }
        for (i in 0 until maxSnow) { fx[i] = r(0f, W); fy[i] = r(0f, H); fr[i] = r(1.5f, 4.2f) * k; fs[i] = r(40f, 95f) * k; fph[i] = r(0f, 6.28f) }
        first = true
    }

    private fun ridge(ys: FloatArray, x: Float): Float {
        val f = (x / W * 40f).coerceIn(0f, 39.999f); val j = f.toInt()
        return ys[j] + (ys[j + 1] - ys[j]) * (f - j)
    }

    private fun pine(p: Path, x: Float, base: Float, h: Float) {
        val w = h * .26f
        p.moveTo(x - w, base); p.lineTo(x, base - h * .62f); p.lineTo(x + w, base); p.close()
        p.moveTo(x - w * .78f, base - h * .38f); p.lineTo(x, base - h); p.lineTo(x + w * .78f, base - h * .38f); p.close()
    }

    // ---------------------------------------------------------------- sprites (drawn once, tinted live)

    private fun makeCloud(w: Int, h: Int, dense: Boolean): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val base = h * .74f
        repeat(if (dense) 24 else 10 + rnd.nextInt(9)) {
            val f = r(.18f, .82f); val hump = sin(f * PI.toFloat())
            val rad = h * r(.16f, .3f) * (.65f + .6f * hump)
            val cx = w * f; val cy = base - rad * r(.25f, .85f) * (.5f + hump * .6f)
            p.shader = RadialGradient(cx, cy, rad, intArrayOf(0xF5FFFFFF.toInt(), 0xC8FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, rad, p)
        }
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)   // shaded underside
        p.shader = LinearGradient(0f, h * .2f, 0f, base, 0x00000000, 0x664A5468, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)    // soft flat base
        p.shader = LinearGradient(0f, base - h * .08f, 0f, base + h * .1f, 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return b
    }

    /** Overcast layer; tiles left-right without a seam. */
    private fun makeDeck(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(80) {
            val cx = r(0f, w.toFloat()); val cy = r(h * .05f, h * .85f)
            val rad = r(.12f, .3f) * h * (1.3f - cy / h * .5f)
            val a = r(.35f, .7f)
            p.shader = RadialGradient(cx, cy, rad, intArrayOf(Color.argb((a * 255).toInt(), 255, 255, 255), Color.argb((a * 110).toInt(), 255, 255, 255), 0),
                floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
            for (dx in floatArrayOf(-w.toFloat(), 0f, w.toFloat())) { c.save(); c.translate(dx, 0f); c.drawCircle(cx, cy, rad, p); c.restore() }
        }
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x00000000, 0x553C4454, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        p.shader = LinearGradient(0f, h * .55f, 0f, h.toFloat(), 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return b
    }

    /** Low fog banks: wide soft ellipses, faded at the top and bottom, tiling left-right without a seam. */
    private fun makeFog(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(0x40FFFFFF)
        p.shader = RadialGradient(0f, 0f, 1f, intArrayOf(0x70FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        repeat(70) {
            val cx = r(0f, w.toFloat()); val cy = r(h * .3f, h * .75f)
            val rx = r(.12f, .3f) * w; val ry = r(.12f, .26f) * h
            for (dx in floatArrayOf(-w.toFloat(), 0f, w.toFloat())) { c.save(); c.translate(cx + dx, cy); c.scale(rx, ry); c.drawCircle(0f, 0f, 1f, p); c.restore() }
        }
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x00000000, 0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
            floatArrayOf(0f, .35f, .8f, 1f), Shader.TileMode.CLAMP)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return b
    }

    private fun makeGlow(n: Int): Bitmap {
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(n / 2f, n / 2f, n / 2f, intArrayOf(0xFFFFFFFF.toInt(), 0xB0FFFFFF.toInt(), 0x3CFFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, .12f, .4f, 1f), Shader.TileMode.CLAMP)
        Canvas(b).drawCircle(n / 2f, n / 2f, n / 2f, p)
        return b
    }

    // ---------------------------------------------------------------- step

    fun update(dt: Float, wx: Wx) {
        if (W == 0f) return
        t += dt
        val snap = first
        fun ease(cur: Float, tgt: Float, tau: Float) = if (snap) tgt else cur + (tgt - cur) * (1f - exp(-dt / tau))
        elev = wx.elev; az = wx.az
        cover = ease(cover, wx.cover, 5f)
        sunOut = ease(sunOut, wx.sunOut, 2.5f)
        rain = ease(rain, wx.rain, 2.5f)
        snow = ease(snow, wx.snow, 3f)
        fog = ease(fog, wx.fog, 6f)
        val dirX = sin(Math.toRadians(wx.windFrom.toDouble())).toFloat()   // + = drifts right (wind from the east)
        windV = ease(windV, dirX * (8f + wx.wind * 4.5f) * k, 4f)
        slant = ease(slant, (dirX * wx.wind * .03f).coerceIn(-.6f, .6f), 3f)
        gusty = ease(gusty, ((wx.gust - wx.wind) / 15f).coerceIn(0f, 1f), 4f)
        val wt = WindFx.now(); WindFx.tick(wt)
        curSlant = slant * (1f + .35f * gusty * sin(t * .9f)) + WindFx.pushDir * WindFx.pulse(wt, .5f) * .25f
        moonAlt = wx.moonAlt ?: -90f; moonAz = wx.moonAz ?: 180f
        bolt = wx.lightning
        first = false

        val show = (cover * 1.08f).coerceIn(0f, 1f)
        for (c in clouds) {
            val hw = c.bmp.width * c.s
            c.x += windV * c.v * dt
            if (windV >= 0 && c.x - hw > W) c.x = -hw - r(0f, W * .25f)
            if (windV < 0 && c.x + hw < 0) c.x = W + hw + r(0f, W * .25f)
            val tgt = if (c.order < show) 1f else 0f
            c.a = if (snap) tgt else c.a + (tgt - c.a) * (1f - exp(-dt / 3f))
        }
        deckX += windV * .35f * dt
        fogX += (windV * .25f + 5f * k) * dt

        rainN = if (rain > .01f) max(60, (maxRain * rain.pow(1.15f)).toInt()).coerceAtMost(maxRain) else 0
        for (i in 0 until rainN) {
            ry[i] += rs[i] * dt; rx[i] += rs[i] * curSlant * dt
            if (ry[i] - rl[i] > H) { ry[i] = r(-H * .3f, -rl[i]); rx[i] = r(-.2f * W, 1.2f * W) }
        }
        if (rain > .05f) {
            splashAcc += dt * rain * 110f
            while (splashAcc >= 1f) {
                splashAcc -= 1f
                val i = splashNext; splashNext = (splashNext + 1) % maxSplash
                spx[i] = r(0f, W); spy[i] = r(H * .8f, H); spAge[i] = 0f
            }
        }
        for (i in 0 until maxSplash) spAge[i] += dt

        snowN = if (snow > .01f) (maxSnow * snow).toInt().coerceIn(40, maxSnow) else 0
        for (i in 0 until snowN) {
            fy[i] += fs[i] * dt
            fx[i] += (windV * .9f + sin(t * .8f + fph[i]) * 18f * k + WindFx.pushDir * WindFx.pulse(wt, fx[i] / W) * 200f * k) * dt
            if (fy[i] > H + 8f) { fy[i] = -8f * k; fx[i] = r(-.1f * W, 1.1f * W) }
            if (fx[i] > W + 10f) fx[i] -= W + 20f else if (fx[i] < -10f) fx[i] += W + 20f
        }

        if (bolt) {
            nextBolt -= dt
            if (nextBolt <= 0f) { flashT = 0f; makeBolt(); nextBolt = r(5f, 16f) }
        }
        if (flashT >= 0f) { flashT += dt; if (flashT > 1.2f) flashT = -1f }
    }

    private fun makeBolt() {
        boltPath.reset()
        var x = r(.15f * W, .85f * W); var y = H * r(.16f, .28f)
        val endY = H * .74f; val steps = 14; val dy = (endY - y) / steps
        boltPath.moveTo(x, y)
        for (i in 0 until steps) {
            x += r(-30f, 30f) * k; y += dy * r(.7f, 1.3f)
            boltPath.lineTo(x, y)
            if (i == 5) {   // one fork
                var bx = x; var by = y
                repeat(5) { bx += r(10f, 34f) * k; by += dy * r(.6f, 1.1f); boltPath.lineTo(bx, by) }
                boltPath.moveTo(x, y)
            }
        }
    }

    private fun flash(): Float {
        val f = flashT
        return when {
            f < 0f -> 0f
            f < .07f -> 1f
            f < .13f -> .25f
            f < .2f -> .8f
            else -> .8f * exp(-(f - .2f) * 6f)
        }
    }

    // ---------------------------------------------------------------- draw

    private val kfE = floatArrayOf(-90f, -12f, -5f, 0f, 4f, 12f, 90f)
    private val kfTop = intArrayOf(0x01020A, 0x04081E, 0x1A1F4D, 0x2B3A74, 0x3C6DB4, 0x3A78C8, 0x2A6CC6)
    private val kfBot = intArrayOf(0x060B1C, 0x151B3E, 0x7A4A7A, 0xF08A5D, 0xF4C58E, 0xBCD8EF, 0xA6CDF0)
    private var skyTop = -1
    private var skyBot = -1
    private var skyShader: Shader? = null

    private fun skyX(a: Float) = W * (.5f + .44f * sin((a - 180f).coerceIn(-125f, 125f) * PI.toFloat() / 250f))
    private fun skyY(e: Float): Float {
        val f = (abs(e) / 62f).pow(.85f) * sign(e)
        return H * .735f - H * .64f * f.coerceIn(-.2f, 1.05f)
    }

    fun draw(c: Canvas) {
        if (W == 0f) return
        val day = ((elev + 6f) / 18f).coerceIn(0f, 1f)
        val night = ((2f - elev) / 12f).coerceIn(0f, 1f)

        // sky
        var top = kf(kfTop, elev); var bot = kf(kfBot, elev)
        val grey = mix(0x14181F, 0x767E8A, day)
        val wk = (cover * .78f + rain * .12f + snow * .1f).coerceIn(0f, .92f)
        top = mix(top, scale(grey, .85f), wk); bot = mix(bot, grey, wk)
        if (rain > 0f) { top = scale(top, 1f - .25f * rain); bot = scale(bot, 1f - .2f * rain) }
        if (top != skyTop || bot != skyBot || skyShader == null) {
            skyTop = top; skyBot = bot
            skyShader = LinearGradient(0f, 0f, 0f, H * .78f, 0xFF000000.toInt() or top, 0xFF000000.toInt() or bot, Shader.TileMode.CLAMP)
        }
        skyPaint.shader = skyShader
        c.drawRect(0f, 0f, W, H, skyPaint)

        // stars
        val starA = night * (1f - cover).pow(1.5f) * (1f - fog * .8f)
        if (starA > .01f) {
            dot.color = Color.WHITE
            for (s in stars) {
                dot.alpha = (255 * starA * s.a * (.6f + .4f * sin(t * s.tw + s.ph))).toInt().coerceIn(0, 255)
                c.drawCircle(s.x, s.y, s.r, dot)
            }
        }

        // moon: NASA's frame is black around the moon, so adding it to the sky leaves just the lit part
        val moon = SkyAssets.moon
        val gl = glow
        if (moon != null && gl != null && moonAlt > -3f) {
            val mx = skyX(moonAz); val my = skyY(moonAlt)
            val dark = ((3f - elev) / 9f).coerceIn(0f, 1f)
            val a = (dark + (1f - dark) * .5f) * (1f - cover * .85f) * (1f - fog * .6f)
            if (a > .02f) {
                bmp.colorFilter = tintFilter(0xC8D7FF); bmp.alpha = (a * dark * 90).toInt()
                val gr = moonR * 5f; dst.set(mx - gr, my - gr, mx + gr, my + gr); c.drawBitmap(gl, null, dst, bmp)
                bmp.colorFilter = null; bmp.xfermode = add; bmp.alpha = (255 * a).toInt()
                val half = moonR / moon.discFrac
                dst.set(mx - half, my - half, mx + half, my + half); c.drawBitmap(moon.img, null, dst, bmp)
                bmp.xfermode = null
            }
        }

        // sun: glow, then today's real disc from SDO
        val sx = skyX(az); val sy = skyY(elev)
        val sunUp = elev > -5f && gl != null
        if (sunUp) {
            val hi = (elev / 14f).coerceIn(0f, 1f)
            val gcol = mix(0xFF9646, 0xFFF6E1, hi)
            val ga = (.75f * sunOut + .3f * (1f - sunOut) * (1f - rain * .7f)) * ((elev + 5f) / 5f).coerceIn(0f, 1f)
            val gr = sunR * (4.5f + 5f * (1f - elev / 10f).coerceIn(0f, 1f)) * (1f + (1f - sunOut) * .7f)
            bmp.colorFilter = tintFilter(gcol); bmp.alpha = (255 * ga).toInt().coerceIn(0, 255)
            dst.set(sx - gr, sy - gr, sx + gr, sy + gr); c.drawBitmap(gl!!, null, dst, bmp)
            val da = sunOut.pow(1.3f) * ((elev + 2f) / 3f).coerceIn(0f, 1f)
            if (da > .01f) {
                val sun = SkyAssets.sun
                // bright rim, so the disc reads as blazing rather than painted on
                bmp.colorFilter = tintFilter(mix(gcol, 0xFFFFFF, .6f)); bmp.alpha = (235 * da).toInt()
                val rr = sunR * 2.1f; dst.set(sx - rr, sy - rr, sx + rr, sy + rr); c.drawBitmap(gl!!, null, dst, bmp)
                if (sun != null) {
                    bmp.colorFilter = sunFilter(elev); bmp.alpha = (255 * da).toInt()
                    dst.set(sx - sunR, sy - sunR, sx + sunR, sy + sunR); c.drawBitmap(sun.disc, null, dst, bmp)
                } else {
                    dot.color = 0xFF000000.toInt() or gcol; dot.alpha = (255 * da).toInt(); c.drawCircle(sx, sy, sunR * .8f, dot)
                }
            }
        }

        // cloud tints follow the light
        // clouds stay lit (pink-orange) through sunset and dusk, well after the ground goes dark
        val cloudLight = ((elev + 8f) / 14f).coerceIn(0f, 1f)
        var tint = mix(0x343A4A, mix(0xFFA27A, 0xFFFFFF, (elev / 12f).coerceIn(0f, 1f)), cloudLight)
        val storm = (rain * .8f + (cover - .7f).coerceAtLeast(0f) * 1.3f + snow * .3f).coerceIn(0f, .85f)
        tint = mix(tint, mix(0x23272F, 0x8A919E, day), storm)
        val deckTint = mix(tint, mix(0x262A33, 0x9AA2AE, day), .45f)

        // overcast layer
        val deckA = smooth(.45f, .95f, cover)
        deck?.let { d ->
            if (deckA > .01f) {
                bmp.colorFilter = tintFilter(deckTint); bmp.alpha = (235 * deckA).toInt()
                val dh = H * .62f; val y0 = -H * .04f
                val x0 = ((deckX % W) + W) % W
                dst.set(x0 - W, y0, x0, y0 + dh); c.drawBitmap(d, null, dst, bmp)
                dst.set(x0, y0, x0 + W, y0 + dh); c.drawBitmap(d, null, dst, bmp)
            }
        }

        // clouds (kept off the sun while the station says it's shining)
        bmp.colorFilter = tintFilter(tint)
        for (cl in clouds) {
            if (cl.a < .01f) continue
            val hw = cl.bmp.width * cl.s; val hh = cl.bmp.height * cl.s
            var a = cl.a * .92f
            if (sunUp) {
                val dx = abs(cl.x - sx) / (hw + sunR); val dy = abs(cl.y - sy) / (hh + sunR)
                a *= 1f - sunOut * .9f * ((1f - max(dx, dy)).coerceIn(0f, .5f) * 2f)
            }
            bmp.alpha = (255 * a).toInt()
            dst.set(cl.x - hw, cl.y - hh, cl.x + hw, cl.y + hh); c.drawBitmap(cl.bmp, null, dst, bmp)
        }
        // the cloud in front of the sun: slides in when the sunlight drops, drifts off when it comes back
        val vb = veil
        if (sunUp && vb != null && sunOut < .98f) {
            val hw = vb.width * 1.1f; val hh = vb.height * 1.1f
            val side = if (windV >= 0) 1f else -1f
            val vx = sx + side * sunOut * (hw + sunR) * 1.1f; val vy = sy + hh * .1f
            bmp.alpha = (255 * (1f - sunOut) * .97f).toInt()
            dst.set(vx - hw, vy - hh, vx + hw, vy + hh); c.drawBitmap(vb, null, dst, bmp)
        }
        bmp.colorFilter = null

        // lightning bolt (behind the hills)
        val fl = flash()
        if (fl > 0f && flashT < .3f) {
            line.color = Color.rgb(200, 210, 255); line.style = Paint.Style.STROKE
            line.alpha = (110 * fl).toInt(); line.strokeWidth = 9f * k; c.drawPath(boltPath, line)
            line.color = Color.WHITE; line.alpha = (255 * fl).toInt(); line.strokeWidth = 2.6f * k; c.drawPath(boltPath, line)
            line.style = Paint.Style.FILL
        }

        // hills
        val lit = day * (1f - cover * .3f) * (1f - rain * .3f)
        for (h in hills) {
            fill.color = 0xFF000000.toInt() or mix(h.night, h.day, lit)
            c.drawPath(h.ground, fill); c.drawPath(h.trees, fill)
        }

        // fog
        val fb = fogBmp
        if (fog > .01f && fb != null) {
            val fc = mix(0x283040, 0xCDD4DC, day)
            bmp.colorFilter = tintFilter(fc); bmp.alpha = (200 * fog).toInt()
            val y0 = H * .5f; val y1 = H * 1.02f; val x0 = ((fogX % W) + W) % W
            dst.set(x0 - W, y0, x0, y1); c.drawBitmap(fb, null, dst, bmp)
            dst.set(x0, y0, x0 + W, y1); c.drawBitmap(fb, null, dst, bmp)
            bmp.colorFilter = null
            fill.color = 0xFF000000.toInt() or fc; fill.alpha = (60 * fog).toInt(); c.drawRect(0f, 0f, W, H, fill)
        }

        // rain
        if (rainN > 0) {
            val br = (.45f + .55f * day) * (.6f + .4f * rain)
            var nf = 0; var nn = 0
            for (i in 0 until rainN) {
                val near = i % 3 == 0
                val arr = if (near) nearPts else farPts
                val o = if (near) nn else nf
                arr[o] = rx[i]; arr[o + 1] = ry[i]; arr[o + 2] = rx[i] - curSlant * rl[i]; arr[o + 3] = ry[i] - rl[i]
                if (near) nn += 4 else nf += 4
            }
            line.style = Paint.Style.STROKE
            line.color = Color.rgb(190, 205, 228)
            line.alpha = (75 * br).toInt(); line.strokeWidth = 1.3f * k; c.drawLines(farPts, 0, nf, line)
            line.alpha = (105 * br).toInt(); line.strokeWidth = 2.2f * k; c.drawLines(nearPts, 0, nn, line)
            line.style = Paint.Style.FILL
            ring.color = Color.rgb(200, 214, 235); ring.strokeWidth = 1.3f * k
            for (i in 0 until maxSplash) {
                val age = spAge[i]
                if (age > .22f) continue
                val f = age / .22f; val rr = (2f + 8f * f) * k
                ring.alpha = (120 * br * (1f - f)).toInt()
                dst.set(spx[i] - rr, spy[i] - rr * .35f, spx[i] + rr, spy[i] + rr * .35f); c.drawOval(dst, ring)
            }
        }

        // snow
        if (snowN > 0) {
            dot.color = Color.WHITE
            val a = (.55f + .45f * day) * (.5f + .5f * snow)
            for (i in 0 until snowN) { dot.alpha = (225 * a).toInt(); c.drawCircle(fx[i], fy[i], fr[i], dot) }
        }

        // lightning flash
        if (fl > 0f) { fill.color = Color.rgb(225, 230, 255); fill.alpha = (150 * fl).toInt(); c.drawRect(0f, 0f, W, H, fill) }
    }

    // ---------------------------------------------------------------- colour helpers

    private val filters = HashMap<Int, ColorFilter>()
    private fun tintFilter(rgb: Int): ColorFilter {
        if (filters.size > 96) filters.clear()
        return filters.getOrPut(rgb and 0xFFFFFF) { LightingColorFilter(rgb and 0xFFFFFF, 0) }
    }

    private var sunCf: ColorFilter? = null
    private var sunCfE = -999f
    /** SDO's picture is deep orange; lift it toward yellow-white when the sun is high, redden it near the horizon. */
    private fun sunFilter(e: Float): ColorFilter {
        sunCf?.let { if (abs(e - sunCfE) < .3f) return it }
        val a = .45f * (e / 12f).coerceIn(0f, 1f)
        val low = ((e + 1f) / 7f).coerceIn(0f, 1f)
        val gK = .72f + .28f * low; val bK = .55f + .45f * low
        val m = ColorMatrix(floatArrayOf(
            1f - a, 0f, 0f, 0f, 255f * a,
            0f, (1f - a) * gK, 0f, 0f, 255f * a * .93f * gK,
            0f, 0f, (1f - a) * bK, 0f, 255f * a * .72f * bK,
            0f, 0f, 0f, 1f, 0f))
        return ColorMatrixColorFilter(m).also { sunCf = it; sunCfE = e }
    }

    private fun kf(cols: IntArray, e: Float): Int {
        var i = 0
        while (i < kfE.size - 2 && e >= kfE[i + 1]) i++
        return mix(cols[i], cols[i + 1], ((e - kfE[i]) / (kfE[i + 1] - kfE[i])).coerceIn(0f, 1f))
    }

    private fun mix(a: Int, b: Int, f: Float): Int {
        val r = ((a shr 16) and 255) + (((b shr 16) and 255) - ((a shr 16) and 255)) * f
        val g = ((a shr 8) and 255) + (((b shr 8) and 255) - ((a shr 8) and 255)) * f
        val bl = (a and 255) + ((b and 255) - (a and 255)) * f
        return (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or bl.toInt().coerceIn(0, 255)
    }

    private fun scale(c: Int, f: Float) = mix(0, c, f)
    private fun smooth(a: Float, b: Float, x: Float): Float { val q = ((x - a) / (b - a)).coerceIn(0f, 1f); return q * q * (3 - 2 * q) }
}

/**
 * adb test hook: renders the Live weather scene off-screen (nothing changes on the TV) to files/<name>.jpg.
 *   am broadcast -a com.cesmotacon.tvhome.SKY -p com.cesmotacon.tvhome [--ef elev 25] [--ef cover .4] [--ef sun 1]
 *     [--ef rain .05 (in/h)] [--ef snow .5] [--ef wind 8] [--ef dir 270] [--ef fog .6] [--ez bolt true]
 *     [--ef moonAlt 30] [--ef moonAz 150] [--es name sky] [--ez apply true [--ei minutes 10]] [--ez clear true]
 * Anything not given uses the real weather. "apply" also shows the result on TV Home for a few minutes.
 */
object SkyDebug {
    fun render(ctx: Context, i: Intent) {
        if (i.getBooleanExtra("clear", false)) { Weather.override = null; Log.i("TvHome.Sky", "override cleared"); return }
        Edition.init(ctx); Place.load(ctx)
        Weather.source.refresh(ctx)
        val until = SystemClock.elapsedRealtime() + 8000
        while (Weather.source.now() == null && SystemClock.elapsedRealtime() < until) Thread.sleep(200)
        runBlocking { SkyAssets.refresh(ctx) }
        val live = Weather.source.now() ?: Weather.fair()
        fun f(n: String): Float? = i.getFloatExtra(n, Float.NaN).takeUnless { it.isNaN() }
        val rate = f("rain")
        val wx = live.copy(
            elev = f("elev") ?: live.elev, az = f("az") ?: live.az,
            cover = f("cover") ?: live.cover, sunOut = f("sun") ?: live.sunOut,
            rain = rate?.let { Weather.rainIntensity(it, it > 0f) } ?: live.rain,
            rainRate = rate ?: live.rainRate, snow = f("snow") ?: live.snow,
            wind = f("wind") ?: live.wind, gust = f("gust") ?: f("wind")?.times(1.4f) ?: live.gust, windFrom = f("dir") ?: live.windFrom,
            fog = f("fog") ?: live.fog, lightning = if (i.hasExtra("bolt")) i.getBooleanExtra("bolt", false) else live.lightning,
            moonAlt = f("moonAlt") ?: live.moonAlt, moonAz = f("moonAz") ?: live.moonAz,
        )
        if (i.getBooleanExtra("apply", false)) {
            Weather.override = wx
            Weather.overrideUntil = SystemClock.elapsedRealtime() + i.getIntExtra("minutes", 10) * 60_000L
        }
        val w = i.getIntExtra("w", 1920); val h = w * 9 / 16
        val sky = LiveSky(); sky.setup(w, h)
        repeat(i.getIntExtra("frames", 90)) { sky.update(1f / 30f, wx) }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        sky.draw(Canvas(bmp))
        val name = (i.getStringExtra("name") ?: "sky").replace(Regex("[^A-Za-z0-9_-]"), "")
        File(ctx.filesDir, "$name.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        Log.i("TvHome.Sky", "rendered $name: ${Weather.describe(wx)} | ${Weather.caption(ctx, wx)}")
    }
}
