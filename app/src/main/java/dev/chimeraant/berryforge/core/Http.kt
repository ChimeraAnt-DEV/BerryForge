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
}
