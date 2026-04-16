package com.opensmarthome.speaker.voice.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber

class AndroidSttProvider(private val context: Context) : SpeechToText {

    private var recognizer: SpeechRecognizer? = null
    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    var language: String? = null
    var silenceTimeoutMs: Long = 1500L

    override fun startListening(): Flow<SttResult> = callbackFlow {
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _isListening.value = true
                Timber.d("STT ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Timber.d("STT speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                _isListening.value = false
                Timber.d("STT end of speech")
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

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

            // Configurable silence timeout (like OpenClaw's 1500ms)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceTimeoutMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceTimeoutMs)

            // Language setting
            if (!language.isNullOrEmpty()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            }
        }

        sr.startListening(intent)
        Timber.d("Android STT started (lang=$language, silence=${silenceTimeoutMs}ms)")

        awaitClose {
            sr.stopListening()
            sr.destroy()
            recognizer = null
            _isListening.value = false
        }
    }

    override fun stopListening() {
        recognizer?.stopListening()
        _isListening.value = false
    }
}
