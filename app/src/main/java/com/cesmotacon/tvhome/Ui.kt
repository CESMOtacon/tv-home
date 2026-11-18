package com.cesmotacon.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Layout is designed in 1920x1080 "design pixels" and scaled to the real screen. */
object Ui {
    var s = 1f
        private set

    fun init(ctx: Context) {
        val dm = ctx.resources.displayMetrics
        s = maxOf(dm.widthPixels, dm.heightPixels) / 1920f
    }

    fun px(v: Number): Int = (v.toFloat() * s).roundToInt()
    fun pxf(v: Number): Float = v.toFloat() * s

    val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val THIN: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    val REG: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val MED: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    const val INK = 0xFFEEF1F6.toInt()
    const val MUTED = 0xFF9AA3B2.toInt()
    const val AMBER = 0xFFF4B24E.toInt()
    const val RED = 0xFFFF4B4B.toInt()
    const val OK = 0xFF4CD28B.toInt()
    const val GLASS = 0x73141620   // rgba(20,22,32,.45)

    fun text(ctx: Context, size: Number, color: Int = INK, tf: Typeface = REG, lines: Int = 1) =
        TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, pxf(size))
            setTextColor(color)
            typeface = tf
            includeFontPadding = false
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(pxf(12), 0f, pxf(2), 0x66000000)
        }

    fun round(color: Int, radius: Number) = GradientDrawable().apply {
        setColor(color); cornerRadius = pxf(radius)
    }

    fun frameLp(w: Int, h: Int, left: Int = 0, top: Int = 0, gravity: Int = Gravity.TOP or Gravity.START) =
        FrameLayout.LayoutParams(w, h, gravity).apply { leftMargin = left; topMargin = top }

    fun linLp(w: Int, h: Int, right: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { rightMargin = right; bottomMargin = bottom }

    const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT

    fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    /** Average "vivid" colour of a drawable, used for the focus glow behind a tile. */
    fun accentOf(d: Drawable?): Int {
        if (d == null) return 0xFF9AA4B8.toInt()
        return try {
            val copy = d.constantState?.newDrawable()?.mutate() ?: d
            val bmp = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888)
            copy.setBounds(0, 0, 24, 14); copy.draw(Canvas(bmp))
            var r = 0.0; var g = 0.0; var b = 0.0; var wsum = 0.0
            val hsv = FloatArray(3)
            for (y in 0 until 14) for (x in 0 until 24) {
                val p = bmp.getPixel(x, y)
                if (Color.alpha(p) < 128) continue
                Color.colorToHSV(p, hsv)
                val w = (hsv[1] * hsv[2]).toDouble() + 0.02
                r += Color.red(p) * w; g += Color.green(p) * w; b += Color.blue(p) * w; wsum += w
            }
            bmp.recycle()
            if (wsum == 0.0) 0xFF9AA4B8.toInt() else {
                Color.colorToHSV(Color.rgb((r / wsum).toInt(), (g / wsum).toInt(), (b / wsum).toInt()), hsv)
                hsv[1] = (hsv[1] * 1.3f).coerceAtMost(1f); hsv[2] = maxOf(hsv[2], 0.75f)
                Color.HSVToColor(hsv)
            }
        } catch (e: Exception) { 0xFF9AA4B8.toInt() }
    }

    /** A small pop-up list for the remote (Back cancels). */
    fun menu(ctx: Context, title: String, items: List<Pair<String, () -> Unit>>) {
        android.app.AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { d, which -> d.dismiss(); items[which].second() }
            .show()
    }
}
