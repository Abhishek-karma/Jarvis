package com.jarvis.core.voice

import com.jarvis.core.common.DispatcherProvider
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VoiceModule {

    @Provides
    @Singleton
    fun provideSttProvider(
        @Named("llm") client: OkHttpClient,
        moshi: Moshi,
        dispatchers: DispatcherProvider,
    ): SttProvider {
        return OpenAiSttProvider(
            baseUrl = "https://api.openai.com/v1",
            apiKeyProvider = { "" }, // Injected at runtime from settings
            client = client,
            moshi = moshi,
            dispatchers = dispatchers,
        )
    }

    @Provides
    @Singleton
    fun provideTtsProvider(
        @Named("llm") client: OkHttpClient,
        moshi: Moshi,
        dispatchers: DispatcherProvider,
    ): TtsProvider {
        return OpenAiTtsProvider(
            baseUrl = "https://api.openai.com/v1",
            apiKeyProvider = { "" }, // Injected at runtime from settings
            client = client,
            moshi = moshi,
            dispatchers = dispatchers,
        )
    }
}
