package com.opendash.app.voice.diagnostics

/**
 * Pure offline voice-profile evaluator.
 *
 * Reports component-level readiness for local STT, local LLM, Android system
 * TTS, and Piper neural TTS. Overall [SupportedMode] never treats Android TTS
 * as fully offline neural TTS, and never infers readiness from a selected
 * preference alone — callers inject concrete readiness booleans/labels.
 */
object OfflineVoiceProfile {

    enum class SupportedMode {
        FullyOffline,
        LocalSTTAndLLMSystemTts,
        NotReady
    }

    data class ComponentStatus(
        val ready: Boolean,
        val label: String,
        val reason: String
    )

    data class Inputs(
        val localStt: ComponentStatus,
        val localLlm: ComponentStatus,
        val systemTts: ComponentStatus,
        val neuralTts: ComponentStatus
    )

    data class Result(
        val supportedMode: SupportedMode,
        val localStt: ComponentStatus,
        val localLlm: ComponentStatus,
        val systemTts: ComponentStatus,
        val neuralTts: ComponentStatus
    ) {
        val components: List<ComponentStatus>
            get() = listOf(localStt, localLlm, systemTts, neuralTts)
    }

    fun evaluate(inputs: Inputs): Result {
        val mode = when {
            inputs.localStt.ready && inputs.localLlm.ready && inputs.neuralTts.ready ->
                SupportedMode.FullyOffline
            inputs.localStt.ready && inputs.localLlm.ready && inputs.systemTts.ready ->
                SupportedMode.LocalSTTAndLLMSystemTts
            else -> SupportedMode.NotReady
        }
        return Result(
            supportedMode = mode,
            localStt = inputs.localStt,
            localLlm = inputs.localLlm,
            systemTts = inputs.systemTts,
            neuralTts = inputs.neuralTts
        )
    }
}
