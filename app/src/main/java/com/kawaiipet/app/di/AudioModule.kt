package com.kawaiipet.app.di

import android.content.Context
import com.kawaiipet.app.audio.AudioPipeline
import com.kawaiipet.app.audio.AudioRecordManager
import com.kawaiipet.app.audio.AudioTrackManager
import com.kawaiipet.app.audio.ModelManager
import com.kawaiipet.app.audio.SherpaSTT
import com.kawaiipet.app.audio.SherpaTTS
import com.kawaiipet.app.util.PreferenceManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides
    @Singleton
    fun provideModelManager(@ApplicationContext context: Context): ModelManager =
        ModelManager(context)

    @Provides
    @Singleton
    fun provideAudioRecordManager(): AudioRecordManager =
        AudioRecordManager()

    @Provides
    @Singleton
    fun provideAudioTrackManager(): AudioTrackManager =
        AudioTrackManager()

    @Provides
    @Singleton
    fun provideSherpaSTT(modelManager: ModelManager): SherpaSTT =
        SherpaSTT(modelManager)

    @Provides
    @Singleton
    fun provideSherpaTTS(modelManager: ModelManager): SherpaTTS =
        SherpaTTS(modelManager)

    @Provides
    @Singleton
    fun provideAudioPipeline(
        @ApplicationContext context: Context,
        stt: SherpaSTT,
        tts: SherpaTTS,
        recorder: AudioRecordManager,
        player: AudioTrackManager,
        preferenceManager: PreferenceManager,
    ): AudioPipeline = AudioPipeline(context, stt, tts, recorder, player, preferenceManager)
}
