package com.opendash.app.tool.translate

/**
 * Abstracts on-device translation so [TranslateToolExecutor] is
 * unit-testable without pulling in real ML Kit classes (which need a
 * live Android runtime and a downloaded per-language-pair model).
 */
interface TranslateEngine {
    suspend fun translate(text: String, sourceLanguageTag: String, targetLanguageTag: String): TranslateResult
}

sealed class TranslateResult {
    data class Translated(val text: String) : TranslateResult()
    data class UnsupportedLanguage(val tag: String) : TranslateResult()
    data class Failed(val reason: String) : TranslateResult()
}
