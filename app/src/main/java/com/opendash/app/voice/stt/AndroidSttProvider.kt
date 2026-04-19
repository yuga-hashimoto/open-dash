package com.opendash.app.voice.stt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale

/**
 * Android SpeechRecognizer wrapper.
 * Reference: OpenClaw Assistant SpeechRecognizerManager.kt
 *
 * Key design decisions from OpenClaw:
 * - Always destroy and recreate recognizer to avoid race conditions
 * - Run all recognizer operations on Main thread (Android requirement)
 * - Use foreground Activity context when available (Service context may fail)
 */
class AndroidSttProvider(private val context: Context) : SpeechToText {

    private var recognizer: SpeechRecognizer? = null
    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    var language: String? = null
    var silenceTimeoutMs: Long = 1500L

    /**
     * Minimum accepted speech length. Fed to SpeechRecognizer's
     * `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS` so brief noises below this
     * threshold don't trigger a final result. Matches the VAD `min speech`
     * knob that offline backends expose, so the user's single setting covers
     * both paths.
     */
    var minSpeechMs: Long = 400L

    override fun startListening(): Flow<SttResult> = callbackFlow {
        val targetLanguage = language ?: Locale.getDefault().toLanguageTag()

        Timber.d("STT: startListening (lang=$targetLanguage, silence=${silenceTimeoutMs}ms, available=${SpeechRecognizer.isRecognitionAvailable(context)})")

        // Destroy previous recognizer to avoid race conditions (OpenClaw pattern)
        withContext(Dispatchers.Main) {
            try {
                recognizer?.destroy()
            } catch (e: Exception) {
                Timber.w("Failed to destroy previous recognizer: ${e.message}")
            }
            recognizer = null

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Timber.e("Speech recognition not available on this device")
                trySend(SttResult.Error("Speech recognition not available"))
                close()
                return@withContext
            }

            // On MIUI / non-Pixel stacks the system-wide
            // `voice_recognition_service` secure setting is often unset,
            // which makes `createSpeechRecognizer(context)` emit
            //   E SpeechRecognizer: no selected voice recognition service
            // and immediately fail with `ERROR_CLIENT`. Naming the
            // Google TTS on-device RecognitionService explicitly avoids
            // that binding hole on any device where Google TTS is
            // installed (effectively all modern Android devices with
            // Play Services). Fall back to the default resolver on the
            // unlikely devices where that component isn't installed so
            // Pixel / AOSP paths still work.
            val explicit = ComponentName(
                "com.google.android.tts",
                "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService",
            )
            val explicitInstalled = runCatching {
                context.packageManager.getServiceInfo(explicit, 0)
                true
            }.getOrDefault(false)
            recognizer = if (explicitInstalled) {
                Timber.d("STT: using explicit recognition service $explicit")
                SpeechRecognizer.createSpeechRecognizer(context, explicit)
            } else {
                Timber.d("STT: using default SpeechRecognizer (no explicit component installed)")
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }

        val sr = recognizer
        if (sr == null) {
            trySend(SttResult.Error("Failed to create speech recognizer"))
            close()
            return@callbackFlow
        }

        // Set listener on Main thread (Android requirement)
        withContext(Dispatchers.Main) {
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                    Timber.d("STT: ready for speech")
                }

                override fun onBeginningOfSpeech() {
                    Timber.d("STT: speech started")
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _isListening.value = false
                    Timber.d("STT: end of speech")
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    val errorName = when (error) {
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
                        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
                        SpeechRecognizer.ERROR_SERVER -> "SERVER"
                        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
                        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
                        else -> "UNKNOWN($error)"
                    }
                    Timber.w("STT error: $errorName")

                    // For critical errors, destroy recognizer (OpenClaw pattern)
                    val isSoftError = error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    if (!isSoftError) {
                        try { sr.destroy() } catch (_: Exception) {}
                        recognizer = null
                    }

                    trySend(SttResult.Error("SpeechRecognizer error: $errorName"))
                    close()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val confidence = scores?.firstOrNull() ?: 1.0f
                    Timber.d("STT result: '$text' (confidence=$confidence)")
                    trySend(SttResult.Final(text, confidence))
                    close()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: return
                    trySend(SttResult.Partial(text))
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceTimeoutMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceTimeoutMs)
            // Unofficial extra for minimum speech length. Now wired to a
            // separate preference (P14.2 VAD knob) rather than silenceTimeoutMs
            // — collapsing the two meant that raising the silence window also
            // required a longer utterance, which was surprising.
            putExtra("android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", minSpeechMs)
        }

        // Start listening on Main thread (Android requirement)
        withContext(Dispatchers.Main) {
            try {
                sr.startListening(intent)
                Timber.d("STT: startListening() called")
            } catch (e: Exception) {
                Timber.e(e, "STT: startListening() failed")
                trySend(SttResult.Error("Failed to start speech recognition: ${e.message}"))
                close()
            }
        }

        awaitClose {
            Timber.d("STT: flow closing, cleaning up recognizer")
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
                try {
                    sr.cancel()
                    sr.destroy()
                } catch (e: Exception) {
                    Timber.w("STT cleanup failed: ${e.message}")
                }
            }
            recognizer = null
            _isListening.value = false
        }
    }

    override fun stopListening() {
        // No-op, flow cancellation triggers cleanup
    }

    /**
     * Picks the best installed `RecognitionService` component.
     *
     * Enumerates all services registered for `android.speech.RecognitionService`
     * via [PackageManager.queryIntentServices] (which only requires the
     * `<queries><intent action="android.speech.RecognitionService"/>` entry
     * we already declare in the manifest — unlike [PackageManager.getServiceInfo]
     * which silently fails on Android 11+ when the package isn't otherwise
     * visible) then ranks them by a known-good priority list.
     *
     * `com.google.android.tts` exposes a `GoogleTTSRecognitionService` that
     * is intended for legacy TTS engine binding and is *not* a real STT
     * backend — binding to it succeeds but every utterance comes back as
     * `NO_MATCH`. We therefore explicitly deprioritize it.
     */
    private fun preferredRecognitionService(context: Context): ComponentName? {
        val pm = context.packageManager
        val intent = Intent("android.speech.RecognitionService")
        val all = runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, 0)
        }.getOrDefault(emptyList())

        if (all.isEmpty()) return null

        // Higher index = higher priority. googlequicksearchbox is the real
        // GMS-backed online recognizer; Samsung/Xiaomi/Honor variants are
        // hit-or-miss but generally better than nothing; google.tts is a
        // known dud that we only fall through to as a last resort.
        val priority = listOf(
            "com.google.android.googlequicksearchbox",
            "com.samsung.android.bixby.agent",
            "com.xiaomi.mibrain.speech",
        )

        val ranked = all
            .map { it.serviceInfo }
            .filter { it.packageName != "com.google.android.tts" } // explicit deny
            .sortedBy { si ->
                val idx = priority.indexOf(si.packageName)
                if (idx >= 0) idx else Int.MAX_VALUE - 1
            }

        val pick = ranked.firstOrNull() ?: return null
        return ComponentName(pick.packageName, pick.name)
    }
}
