package com.cesmotacon.tvhome

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One HTTP client and a small background pool for the whole app. */
object Net {
    const val USER_AGENT = "TvHome/1.0 (+https://github.com/CESMOtacon/tv-home)"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val io: ExecutorService = Executors.newFixedThreadPool(2)

    fun request(url: String): Request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

    /** Blocking GET returning the body as text. Call off the main thread. */
    fun get(url: String): String = http.newCall(request(url)).execute().use { r ->
        if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} for $url")
        r.body!!.string()
    }
}
