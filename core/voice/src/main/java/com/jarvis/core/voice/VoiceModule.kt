package com.jarvis.core.voice

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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

    /** Free, high-quality on-device neural TTS engine — 100% offline, zero API keys. */
    @Provides
    @Singleton
    fun provideTtsProvider(
        androidTtsProvider: AndroidTtsProvider,
    ): TtsProvider = androidTtsProvider

    @Provides
    @Singleton
    fun provideVoiceStateMachine(): VoiceStateMachine = VoiceStateMachine()
}
