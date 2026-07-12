package com.opendash.app.tool.memory.embedding

import android.content.Context
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import timber.log.Timber
import java.io.File

/**
 * Production [SemanticEmbedder] backed by MediaPipe's `TextEmbedder`
 * task loaded with the downloaded EmbeddingGemma bundle. Tokenization
 * is handled entirely inside the MediaPipe native runtime.
 *
 * [SemanticEmbedder.Role] is accepted but currently unused: newer
 * MediaPipe releases add a `TextFormatContext` overload of `embed()`
 * that lets EmbeddingGemma apply its recommended query-vs-document
 * prompt framing (Google's Gecko-style formatting), but this project
 * pins `mediapipe-genai`/`mediapipe-text` to 0.10.29 for the LLM
 * engine's stability — that overload doesn't exist at this version
 * (confirmed by an actual compile failure against the resolved AAR
 * during implementation, not assumed). Bumping the shared MediaPipe
 * version just for this would risk the LLM path, which is out of
 * scope for a memory-search quality improvement. Plain `embed(text)`
 * still produces usable embeddings, just without that extra framing.
 */
class MediaPipeSemanticEmbedder(
    context: Context,
    modelFile: File
) : SemanticEmbedder, AutoCloseable {

    private val embedder: TextEmbedder? = runCatching {
        TextEmbedder.createFromFile(context, modelFile)
    }.onFailure {
        Timber.e(it, "Failed to load EmbeddingGemma model from ${modelFile.absolutePath}")
    }.getOrNull()

    override fun isAvailable(): Boolean = embedder != null

    override fun embed(text: String, role: SemanticEmbedder.Role): FloatArray? {
        val e = embedder ?: return null
        return runCatching {
            val result = e.embed(text)
            result.embeddingResult().embeddings().first().floatEmbedding()
        }.onFailure {
            Timber.w(it, "EmbeddingGemma inference failed")
        }.getOrNull()
    }

    override fun close() {
        runCatching { embedder?.close() }
    }
}
