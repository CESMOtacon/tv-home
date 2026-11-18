package com.cesmotacon.tvhome

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

object Apps {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * The Apps row, in order. A fresh install starts with whichever [Config.DEFAULT_FAVORITES] are installed
     * (topped up with other apps if that's only a few); after that it's whatever the viewer arranged.
     */
    fun favorites(ctx: Context): MutableList<String> {
        prefs(ctx).getString("favorites", null)?.let { s -> return s.split(',').filter { it.isNotBlank() }.toMutableList() }
        val pm = ctx.packageManager
        val installed = { p: String -> pm.getLeanbackLaunchIntentForPackage(p) != null || pm.getLaunchIntentForPackage(p) != null }
        val list = Config.DEFAULT_FAVORITES.filter(installed).take(8).toMutableList()
        if (list.size < 4) all(ctx).map { it.pkg }.filter { it !in list }.take(6 - list.size).forEach { list.add(it) }
        return list
    }

    fun setFavorites(ctx: Context, list: List<String>) {
        prefs(ctx).edit().putString("favorites", list.distinct().joinToString(",")).apply()
    }

    fun isFavorite(ctx: Context, pkg: String) = pkg in favorites(ctx)
    fun addFavorite(ctx: Context, pkg: String) = setFavorites(ctx, favorites(ctx).apply { remove(pkg); add(pkg) })
    fun removeFavorite(ctx: Context, pkg: String) = setFavorites(ctx, favorites(ctx).apply { remove(pkg) })

    /** Moves an app one place left (-1) or right (+1) in the Apps row. */
    fun move(ctx: Context, pkg: String, by: Int) {
        val l = favorites(ctx)
        val i = l.indexOf(pkg)
        val j = i + by
        if (i < 0 || j < 0 || j >= l.size) return
        l.removeAt(i); l.add(j, pkg)
        setFavorites(ctx, l)
    }

    class Entry(val pkg: String, val label: String, val banner: Drawable?, val icon: Drawable?, val intent: Intent)

    fun entry(ctx: Context, pkg: String): Entry? {
        val pm = ctx.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return null
        return try {
            val ai = pm.getApplicationInfo(pkg, 0)
            if (!ai.enabled) return null
            val banner = runCatching { pm.getActivityBanner(intent) }.getOrNull() ?: runCatching { pm.getApplicationBanner(pkg) }.getOrNull()
            Entry(pkg, pm.getApplicationLabel(ai).toString(), banner, runCatching { pm.getApplicationIcon(pkg) }.getOrNull(), intent)
        } catch (e: PackageManager.NameNotFoundException) { null }
    }

    /** Every app with a TV launcher entry, sorted by name (ours excluded). */
    fun all(ctx: Context): List<Entry> {
        val pm = ctx.packageManager
        val pkgs = LinkedHashSet<String>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0).forEach { pkgs.add(it.activityInfo.packageName) }
        }
        pkgs.remove(ctx.packageName)
        return pkgs.mapNotNull { entry(ctx, it) }.sortedBy { it.label.lowercase() }
    }

    fun launch(ctx: Context, e: Entry) {
        runCatching { ctx.startActivity(Intent(e.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun stockHomeIntent(): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        .setComponent(ComponentName(Config.STOCK_HOME_PKG, Config.STOCK_HOME_CLS)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Banner tile (or icon + name on a colour wash when the app has no TV banner). */
    fun tile(ctx: Context, e: Entry, w: Int, h: Int): TileView {
        val t = TileView(ctx, w, h, Ui.pxf(22), 0xFF1C1F28.toInt())
        if (e.banner != null) {
            t.addView(ImageView(ctx).apply { setImageDrawable(e.banner); scaleType = ImageView.ScaleType.CENTER_CROP }, 0, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            t.accent = Ui.accentOf(e.banner)
        } else {
            val acc = Ui.accentOf(e.icon)
            t.accent = acc
            t.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(acc, Ui.withAlpha(darken(acc), 255))).apply { cornerRadius = Ui.pxf(22) }
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            col.addView(ImageView(ctx).apply { setImageDrawable(e.icon) }, LinearLayout.LayoutParams(Ui.px(h * 0.42f / Ui.s), Ui.px(h * 0.42f / Ui.s)))
            col.addView(Ui.text(ctx, 21, Color.WHITE, Ui.MED).apply { text = e.label; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.px(8) })
            t.addView(col, 0, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        }
        t.contentDescription = e.label
        t.onClick = { launch(ctx, e) }
        return t
    }

    private fun darken(c: Int): Int {
        val hsv = FloatArray(3); Color.colorToHSV(c, hsv); hsv[2] *= 0.45f; return Color.HSVToColor(hsv)
    }
}
