package com.opendash.app.di

import android.content.Context
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.voice.stt.AndroidSttProvider
import com.opendash.app.voice.stt.DelegatingSttProvider
import com.opendash.app.voice.stt.OfflineSttStub
import com.opendash.app.voice.stt.SpeechToText
import com.opendash.app.voice.stt.whisper.AudioRecordPcmSource
import com.opendash.app.voice.stt.whisper.WhisperCppBridge
import com.opendash.app.voice.stt.whisper.WhisperModelDownloader
import com.opendash.app.voice.stt.whisper.WhisperSttProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * SpeechToText binding lives in its own module so instrumented tests can
 * swap the implementation via `@TestInstallIn(replaces = [SttModule::class])`
 * without rebuilding the rest of the voice graph (TTS, VoicePipeline,
 * LatencyRecorder).
 *
 * See `app/src/androidTest/.../FakeSttTestModule.kt`.
 */
@Module
@InstallIn(SingletonComponent::class)
object SttModule {

    @Provides
    @Singleton
    fun provideSpeechToText(
        @ApplicationContext context: Context,
        preferences: AppPreferences
    ): SpeechToText = DelegatingSttProvider(
        preferences = preferences,
        android = AndroidSttProvider(context),
        whisperOffline = buildWhisperDelegate(context)
    )

    /**
     * The whisper route returns a real [WhisperSttProvider] when the native
     * library can load, else falls back to the stub so user-facing error
     * messages still say "coming soon" rather than silently hanging.
     * `WhisperCppBridge.isAvailable()` checks both submodule + native lib
     * state at call time so runtime toggling (e.g. feature flag, future
     * downloadable-extension pattern) stays honest.
     */
    private fun buildWhisperDelegate(context: Context): SpeechToText {
        return if (WhisperCppBridge.isAvailable()) {
            WhisperSttProvider(
                bridge = WhisperCppBridge(),
                pcmSource = AudioRecordPcmSource(context),
                modelPathProvider = { File(context.filesDir, "whisper/ggml-tiny.bin") }
            )
        } else {
            OfflineSttStub("Whisper")
        }
    }

    /**
     * P14.1 model downloader, shared across Settings UI and any
     * first-run bootstrap code that wants to prefetch a model.
     */
    @Provides
    @Singleton
    fun provideWhisperModelDownloader(
        @ApplicationContext context: Context
    ): WhisperModelDownloader = WhisperModelDownloader(context)
}
