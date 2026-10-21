package dev.chimeraant.berryforge.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * A single shared OkHttp client. Read timeout is generous because on-device Gradle
 * downloads and LLM streaming both produce long-lived connections; connect timeout
 * stays short so offline failures surface fast.
 */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }

    val longClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(0, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Client for the toolchain downloads. Large enough to stream a 100 MB JDK without
     * tripping, but the read timeout is an *inactivity* timeout: a connection that
     * stops delivering bytes (the classic flaky-mobile stall) trips after 60 s so the
     * installer's retry loop can recover instead of hanging forever. `callTimeout` stays
     * zero because the whole transfer legitimately takes minutes.
     */
    val downloadClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
