package com.opendash.app.di

import android.content.Context
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.voice.stt.AndroidSttProvider
import com.opendash.app.voice.stt.DelegatingSttProvider
import com.opendash.app.voice.stt.OfflineSttStub
import com.opendash.app.voice.stt.SpeechToText
import com.opendash.app.voice.stt.whisper.AmplitudeVad
import com.opendash.app.voice.stt.whisper.AudioRecordPcmSource
import com.opendash.app.voice.stt.whisper.VadEngine
import com.opendash.app.voice.stt.whisper.WhisperCppBridge
import com.opendash.app.voice.stt.whisper.WhisperModelCatalog
import com.opendash.app.voice.stt.whisper.WhisperModelDownloader
import com.opendash.app.voice.stt.whisper.WhisperSttProvider
import com.opendash.app.voice.vad.silero.OrtSileroVadSession
import com.opendash.app.voice.vad.silero.SileroVadEngine
import com.opendash.app.voice.vad.silero.SileroVadModelDownloader
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import timber.log.Timber

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
                pcmSource = AudioRecordPcmSource(context, vadFactory = { buildVadEngine(context) }),
                modelPathProvider = { resolveWhisperModelFile(context, preferences) },
                languageProvider = { resolveWhisperLanguage(preferences) },
                translateProvider = { resolveWhisperTranslate(preferences) }
            )
        } else {
            OfflineSttStub("Whisper")
        }
    }

    /**
     * P16.2: uses the neural [SileroVadEngine] when its model has been
     * downloaded (no Settings toggle needed — same auto-upgrade pattern
     * as [WhisperCppBridge.isAvailable] gating the real STT provider),
     * otherwise falls back to the always-available [AmplitudeVad]. Any
     * failure loading the ONNX session (corrupt file, incompatible
     * device) also falls back rather than leaving VAD entirely broken.
     */
    private fun buildVadEngine(context: Context): VadEngine {
        val downloader = SileroVadModelDownloader(context)
        if (downloader.isDownloaded()) {
            val session = OrtSileroVadSession.create(downloader.modelFile())
            if (session != null) return SileroVadEngine(session)
            Timber.w("Silero VAD model present but failed to load; falling back to amplitude VAD")
        }
        return AmplitudeVad(sampleRate = AudioRecordPcmSource.SAMPLE_RATE)
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
     * Reads [PreferenceKeys.WHISPER_LANGUAGE] at call time. Empty /
     * unset falls back to "auto" which matches whisper.cpp's
     * detect-then-transcribe behaviour.
     */
    internal fun resolveWhisperLanguage(preferences: AppPreferences): String {
        val raw = runBlocking {
            preferences.observe(PreferenceKeys.WHISPER_LANGUAGE).first()
        }
        return raw?.trim().orEmpty().ifBlank { "auto" }
    }

    /**
     * Reads [PreferenceKeys.WHISPER_TRANSLATE_TO_ENGLISH] at call time.
     * Empty / unset → false (transcribe in source language).
     */
    internal fun resolveWhisperTranslate(preferences: AppPreferences): Boolean {
        return runBlocking {
            preferences.observe(PreferenceKeys.WHISPER_TRANSLATE_TO_ENGLISH).first()
        } ?: false
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

    /**
     * P16.2 Silero VAD model downloader. Not yet surfaced in a Settings
     * card (no UI trigger to download it exists today) — injectable so
     * that follow-up work only needs to add the UI, not more DI.
     */
    @Provides
    @Singleton
    fun provideSileroVadModelDownloader(
        @ApplicationContext context: Context
    ): SileroVadModelDownloader = SileroVadModelDownloader(context)
}
