package com.cesmotacon.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * TV Home's background helper. It's an accessibility service because that's the only way an app can hear the
 * remote's Home button on Google TV (Google's home screen can't be replaced the normal way).
 *  - Settings → "Home button opens TV Home" (off by default) sends Home to TV Home instead of Google's home screen.
 *  - Editions use it for things that must work over any app (the Home Assistant edition's doorbell pop-up).
 * It reads no screen content: it only listens for keys and for Google's home screen opening.
 */
class HomeKeyService : AccessibilityService() {
    companion object {
        private const val TAG = "TvHome.Svc"
        @Volatile var instance: HomeKeyService? = null
        /** Until then, Google's home screen may stay up (opened on purpose from All apps). */
        @Volatile var allowStockUntil = 0L
    }

    val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        Edition.init(this)
        Edition.onHelperConnected(this)
    }

    override fun onDestroy() {
        Edition.onHelperDestroyed(this)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (Edition.onHelperKey(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_HOME && Prefs.takeOverHome(this)) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) goHome()
            return true
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Safety net: if Google's home screen comes up some other way, switch to ours (unless it was opened on purpose).
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && Prefs.takeOverHome(this) &&
            event.packageName == Config.STOCK_HOME_PKG && event.className?.toString() == Config.STOCK_HOME_CLS &&
            System.currentTimeMillis() > allowStockUntil) {
            val now = System.currentTimeMillis()
            while (bounces.isNotEmpty() && now - bounces.first() > 15_000) bounces.removeFirst()
            if (bounces.size >= 3) {   // TV Home keeps failing to stay up: leave Google's home alone for a while
                Log.w(TAG, "too many bounces; leaving the stock home screen alone for 5 minutes")
                allowStockUntil = now + 5 * 60_000L
                return
            }
            bounces.addLast(now)
            Log.i(TAG, "stock home appeared; switching to TV Home")
            goHome()
        }
    }

    private val bounces = ArrayDeque<Long>()

    override fun onInterrupt() {}

    fun goHome() {
        allowStockUntil = 0L
        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }.onFailure { Log.w(TAG, "goHome failed", it) }
    }
}
