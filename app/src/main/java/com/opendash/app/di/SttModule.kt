package com.opendash.app.di

import android.content.Context
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.voice.stt.AndroidSttProvider
import com.opendash.app.voice.stt.DelegatingSttProvider
import com.opendash.app.voice.stt.OfflineSttStub
import com.opendash.app.voice.stt.SpeechToText
import com.opendash.app.voice.stt.whisper.AudioRecordPcmSource
import com.opendash.app.voice.stt.whisper.WhisperCppBridge
import com.opendash.app.voice.stt.whisper.WhisperModelCatalog
import com.opendash.app.voice.stt.whisper.WhisperModelDownloader
import com.opendash.app.voice.stt.whisper.WhisperSttProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
        whisperOffline = buildWhisperDelegate(context, preferences)
    )

    /**
     * The whisper route returns a real [WhisperSttProvider] when the native
     * library can load, else falls back to the stub so user-facing error
     * messages still say "coming soon" rather than silently hanging.
     *
     * `modelPathProvider` resolves the active model's on-disk path by
     * reading [PreferenceKeys.WHISPER_ACTIVE_MODEL_ID] at call time —
     * so users can switch between tiny / base / small from Settings
     * without rebuilding the VM graph. Unknown / missing ids fall back
     * to the catalogue default (smallest), keeping behaviour sensible
     * if a preference references a model that's been removed from a
     * future catalogue update.
     */
    private fun buildWhisperDelegate(
        context: Context,
        preferences: AppPreferences
    ): SpeechToText {
        return if (WhisperCppBridge.isAvailable()) {
            WhisperSttProvider(
                bridge = WhisperCppBridge(),
                pcmSource = AudioRecordPcmSource(context),
                modelPathProvider = { resolveWhisperModelFile(context, preferences) }
            )
        } else {
            OfflineSttStub("Whisper")
        }
    }

    internal fun resolveWhisperModelFile(
        context: Context,
        preferences: AppPreferences
    ): File {
        val dir = File(context.filesDir, "whisper").apply { mkdirs() }
        // Read the preference synchronously — the provider is on the STT
        // dispatcher at call time and a runBlocking here is bounded to
        // one DataStore read.
        val activeId = runBlocking {
            preferences.observe(PreferenceKeys.WHISPER_ACTIVE_MODEL_ID).first()
        }
        val model = WhisperModelCatalog.all.firstOrNull { it.id == activeId }
            ?: WhisperModelCatalog.default
        return File(dir, model.filename)
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
