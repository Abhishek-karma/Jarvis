package com.jarvis.core.voice

import android.content.Context
import com.jarvis.core.common.DispatcherProvider
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VoiceModule {
    /** On-device STT — no API key, works offline on most modern Android devices. */
    @Provides
    @Singleton
    fun provideSttProvider(
        @ApplicationContext context: Context,
    ): SttProvider = AndroidSttProvider(context)

    /** On-device TTS — no API key, uses the system TTS engine (Google TTS, Samsung TTS, etc.). */
    @Provides
    @Singleton
    fun provideTtsProvider(
        @ApplicationContext context: Context,
    ): TtsProvider = AndroidTtsProvider(context)


    @Provides
    @Named("openai_stt")
    fun provideOpenAiSttProvider(
        @Named("llm") client: OkHttpClient,
        moshi: Moshi,
        dispatchers: DispatcherProvider,
    ): SttProvider =
        OpenAiSttProvider(
            baseUrl = "https://api.openai.com/v1",
            apiKeyProvider = { "" },
            client = client,
            moshi = moshi,
            dispatchers = dispatchers,
        )


    @Provides
    @Named("openai_tts")
    fun provideOpenAiTtsProvider(
        @Named("llm") client: OkHttpClient,
        moshi: Moshi,
        dispatchers: DispatcherProvider,
    ): TtsProvider =
        OpenAiTtsProvider(
            baseUrl = "https://api.openai.com/v1",
            apiKeyProvider = { "" },
            client = client,
            moshi = moshi,
            dispatchers = dispatchers,
        )
}
