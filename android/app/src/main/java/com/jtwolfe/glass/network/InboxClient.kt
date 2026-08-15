package com.jtwolfe.glass.network

import com.jtwolfe.glass.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object InboxClient {

    val isConfigured: Boolean
        get() = BuildConfig.INBOX_URL.isNotBlank() && BuildConfig.INBOX_TOKEN.isNotBlank()

    val api: InboxApi? by lazy {
        if (!isConfigured) return@lazy null

        val baseUrl = BuildConfig.INBOX_URL.let { url ->
            if (url.endsWith("/")) url else "$url/"
        }

        val authInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer ${BuildConfig.INBOX_TOKEN}")
                .addHeader("Content-Type", "application/json")
                .build()
            chain.proceed(request)
        }

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(InboxApi::class.java)
    }
}
