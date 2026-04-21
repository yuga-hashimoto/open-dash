package com.opendash.app.voice.stt.whisper

import timber.log.Timber

/**
 * JNI bridge to the whisper.cpp native library (Phase 16 P14.1).
 *
 * Mirrors [com.opendash.app.assistant.provider.embedded.LlamaCppBridge]:
 *
 * - `System.loadLibrary("whisper_jni")` is attempted lazily via
 *   [tryLoadLibrary]. If the shared library is not shipped in the APK
 *   (the default state today — `externalNativeBuild` is disabled in
 *   `app/build.gradle.kts`), the bridge falls back to [isAvailable] = false
 *   without crashing.
 * - Model lifecycle: [loadModel] → [transcribe]* → [unload].
 * - PCM input is float32 mono at 16 kHz; callers must resample before
 *   handing samples to [transcribe].
 *
 * This class is the plumbing layer — it is NOT a full SttProvider. The
 * follow-up [com.opendash.app.voice.stt.DelegatingSttProvider] swap in
 * Phase 16 item 4 will own AudioRecord capture, VAD, streaming, and
 * language configuration; this bridge is deliberately dumb so it stays
 * small and JNI-boundary-testable.
 *
 * Build instructions (mirrors LlamaCppBridge):
 *
 * 1. `git submodule update --init app/src/main/cpp/whisper.cpp`
 * 2. Re-enable `externalNativeBuild` in `app/build.gradle.kts` (see
 *    the commented block that references NDK 27.0.12077973)
 * 3. CMake produces `libwhisper_jni.so` under arm64-v8a; it's packaged
 *    into the APK automatically by the Android gradle plugin
 */
open class WhisperCppBridge {

    private var contextHandle: Long = 0L
    private var isLoaded = false

    /**
     * Loads a GGML-format whisper model (`ggml-tiny.bin`, `ggml-base.bin`,
     * …) into native memory. Returns `false` when either the native
     * library isn't shipped or whisper rejects the model file (typically
     * a wrong-format or truncated-download failure).
     */
    open fun loadModel(path: String): Boolean {
        if (!tryLoadLibrary()) {
            Timber.e("Cannot load whisper model: native library not available")
            return false
        }
        return try {
            contextHandle = nativeLoadModel(path)
            isLoaded = contextHandle != 0L
            isLoaded
        } catch (e: Exception) {
            Timber.e(e, "Failed to load whisper model: $path")
            false
        }
    }

    /** Visible for test overrides; returns the current loaded state. */
    open fun isModelLoaded(): Boolean = isLoaded

    /**
     * Runs whisper_full on a PCM buffer and returns the transcribed text.
     *
     * @param samples 16 kHz mono float32 PCM in [-1.0, 1.0]. The caller
     *   is responsible for resampling from the AudioRecord capture rate.
     * @param language ISO 639-1 code ("en", "ja") or "auto".
     * @param translate when true, whisper translates non-English speech
     *   into English; when false, it transcribes in the source language.
     */
    open fun transcribe(
        samples: FloatArray,
        language: String = "auto",
        translate: Boolean = false
    ): String {
        check(isLoaded) { "whisper model not loaded" }
        return nativeTranscribe(contextHandle, samples, language, translate)
    }

    fun unload() {
        if (isLoaded && contextHandle != 0L) {
            nativeUnloadModel(contextHandle)
            contextHandle = 0L
            isLoaded = false
        }
    }

    /** `true` when both the native lib is present AND a model has been loaded. */
    fun isReady(): Boolean = libraryLoaded && isLoaded

    private external fun nativeLoadModel(path: String): Long
    private external fun nativeTranscribe(
        handle: Long,
        samples: FloatArray,
        language: String,
        translate: Boolean
    ): String
    private external fun nativeUnloadModel(handle: Long)

    companion object {
        @Volatile
        private var libraryLoaded = false

        /** Kept package-private for tests; production callers use [isAvailable]. */
        internal fun isLibraryLoadedForTest(): Boolean = libraryLoaded

        fun tryLoadLibrary(): Boolean {
            if (libraryLoaded) return true
            return try {
                System.loadLibrary("whisper_jni")
                libraryLoaded = true
                true
            } catch (e: UnsatisfiedLinkError) {
                Timber.w("whisper_jni native library not available: ${e.message}")
                false
            }
        }

        /**
         * `true` when the native library can be loaded. Safe to call
         * repeatedly — caches the result after the first successful load.
         * Returns `false` on stock builds today because
         * `externalNativeBuild` is commented out in `app/build.gradle.kts`.
         */
        fun isAvailable(): Boolean = tryLoadLibrary()
    }
}
