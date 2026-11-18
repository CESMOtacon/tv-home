package com.cesmotacon.tvhome

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArraySet

/** Enabled as a notification listener only so we may read other apps' media sessions (for album art). */
class MediaWatcher : NotificationListenerService() {
    override fun onListenerConnected() { NowPlaying.attach(this) }
    override fun onListenerDisconnected() { NowPlaying.detach() }
}

object NowPlaying {
    class Track(val pkg: String, val title: String, val artist: String, val art: Bitmap?, val playing: Boolean)

    @Volatile var current: Track? = null
        private set
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var msm: MediaSessionManager? = null
    private var ctx: Context? = null
    private val callbacks = HashMap<MediaController, MediaController.Callback>()
    private var artKey: String? = null
    private var artCache: Bitmap? = null

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> track(list ?: emptyList()) }

    fun attach(c: Context) {
        ctx = c.applicationContext
        val m = c.getSystemService(MediaSessionManager::class.java) ?: return
        msm = m
        val cn = ComponentName(c, MediaWatcher::class.java)
        runCatching {
            m.addOnActiveSessionsChangedListener(sessionsListener, cn, main)
            track(m.getActiveSessions(cn))
        }
    }

    fun detach() {
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
        callbacks.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        callbacks.clear()
        set(null)
    }

    private fun track(list: List<MediaController>) {
        callbacks.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        callbacks.clear()
        for (c in list) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = recompute()
                override fun onMetadataChanged(metadata: MediaMetadata?) = recompute()
                override fun onSessionDestroyed() = recompute()
            }
            c.registerCallback(cb, main)
            callbacks[c] = cb
        }
        recompute()
    }

    private fun recompute() {
        val best = callbacks.keys
            .filter { it.packageName !in Config.NOT_MUSIC && it.packageName !in Edition.notMusic() && it.packageName != ctx?.packageName }
            .filter { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null }
            .maxByOrNull { if (it.playbackState?.state == PlaybackState.STATE_PLAYING) 1 else 0 }
        if (best == null) { set(null); return }
        val md = best.metadata!!
        val playing = best.playbackState?.state == PlaybackState.STATE_PLAYING
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val key = "${best.packageName}|$title|$artist"
        if (bmp != null) { artKey = key; artCache = bmp }
        val art = if (artKey == key) artCache else null
        set(Track(best.packageName, title, artist, art, playing))
        if (art == null) {
            val uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            if (uri != null) io.launch {
                val loaded = loadUri(uri)
                if (loaded != null) withContext(Dispatchers.Main) {
                    artKey = key; artCache = loaded
                    val cur = current
                    if (cur != null && "${cur.pkg}|${cur.title}|${cur.artist}" == key) set(Track(cur.pkg, cur.title, cur.artist, loaded, cur.playing))
                }
            }
        }
    }

    private fun loadUri(s: String): Bitmap? = try {
        if (s.startsWith("http")) Net.http.newCall(Net.request(s)).execute().use { r ->
            r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } else ctx?.contentResolver?.openInputStream(Uri.parse(s))?.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) { null }

    private fun set(t: Track?) {
        val old = current
        if (old?.pkg == t?.pkg && old?.title == t?.title && old?.artist == t?.artist && old?.playing == t?.playing && old?.art === t?.art) return
        current = t
        main.post { listeners.forEach { runCatching { it() } } }
    }
}
