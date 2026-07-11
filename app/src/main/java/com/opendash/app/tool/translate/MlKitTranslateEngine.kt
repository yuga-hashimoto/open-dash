package com.opendash.app.tool.translate

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real, on-device [TranslateEngine] backed by ML Kit Translation
 * (`com.google.mlkit:translate` — the standalone artifact, not
 * Firebase ML, so no Firebase project/`google-services.json` is
 * needed). The language-pair model downloads on first use per pair
 * (~30MB) and is cached by ML Kit itself for subsequent calls.
 */
class MlKitTranslateEngine : TranslateEngine {

    override suspend fun translate(text: String, sourceLanguageTag: String, targetLanguageTag: String): TranslateResult {
        val sourceLanguage = TranslateLanguage.fromLanguageTag(sourceLanguageTag)
            ?: return TranslateResult.UnsupportedLanguage(sourceLanguageTag)
        val targetLanguage = TranslateLanguage.fromLanguageTag(targetLanguageTag)
            ?: return TranslateResult.UnsupportedLanguage(targetLanguageTag)

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(targetLanguage)
            .build()
        val translator = Translation.getClient(options)

        return try {
            awaitTask(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            val translated = awaitTask(translator.translate(text))
            TranslateResult.Translated(translated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "ML Kit translation failed")
            TranslateResult.Failed(e.message ?: "Translation failed")
        } finally {
            translator.close()
        }
    }

    private suspend fun <T> awaitTask(task: Task<T>): T = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { result -> cont.resume(result) }
        task.addOnFailureListener { e -> cont.resumeWithException(e) }
    }
}
