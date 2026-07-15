package com.opendash.app.voice.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OfflineVoiceProfileTest {

    @Test
    fun `all local gates ready reports FullyOffline`() {
        val result = OfflineVoiceProfile.evaluate(
            OfflineVoiceProfile.Inputs(
                localStt = ready("Local STT", "Whisper native + model ready"),
                localLlm = ready("Local LLM", "Embedded model on disk"),
                systemTts = ready("System TTS", "Android TTS engine present"),
                neuralTts = ready("Neural TTS", "Piper native + voice ready")
            )
        )

        assertThat(result.supportedMode).isEqualTo(OfflineVoiceProfile.SupportedMode.FullyOffline)
        assertThat(result.localStt.ready).isTrue()
        assertThat(result.localLlm.ready).isTrue()
        assertThat(result.neuralTts.ready).isTrue()
        assertThat(result.neuralTts.label).isEqualTo("Neural TTS")
    }

    @Test
    fun `local STT and LLM with Android TTS only reports LocalSTTAndLLMSystemTts`() {
        val result = OfflineVoiceProfile.evaluate(
            OfflineVoiceProfile.Inputs(
                localStt = ready("Local STT", "Whisper native + model ready"),
                localLlm = ready("Local LLM", "Embedded model on disk"),
                systemTts = ready("System TTS", "Android TTS engine present"),
                neuralTts = notReady(
                    "Neural TTS",
                    "Piper native library unavailable; not fully offline neural TTS"
                )
            )
        )

        assertThat(result.supportedMode)
            .isEqualTo(OfflineVoiceProfile.SupportedMode.LocalSTTAndLLMSystemTts)
        assertThat(result.systemTts.ready).isTrue()
        assertThat(result.neuralTts.ready).isFalse()
        assertThat(result.neuralTts.reason).contains("not fully offline neural TTS")
    }

    @Test
    fun `missing local STT reports NotReady`() {
        val result = OfflineVoiceProfile.evaluate(
            OfflineVoiceProfile.Inputs(
                localStt = notReady("Local STT", "Whisper model not downloaded"),
                localLlm = ready("Local LLM", "Embedded model on disk"),
                systemTts = ready("System TTS", "Android TTS engine present"),
                neuralTts = ready("Neural TTS", "Piper native + voice ready")
            )
        )

        assertThat(result.supportedMode).isEqualTo(OfflineVoiceProfile.SupportedMode.NotReady)
        assertThat(result.localStt.ready).isFalse()
        assertThat(result.localStt.reason).contains("Whisper")
    }

    @Test
    fun `piper native unavailable is never FullyOffline even when preference would select piper`() {
        val result = OfflineVoiceProfile.evaluate(
            OfflineVoiceProfile.Inputs(
                localStt = ready("Local STT", "Whisper native + model ready"),
                localLlm = ready("Local LLM", "Embedded model on disk"),
                systemTts = ready("System TTS", "Android TTS engine present"),
                // Preference may say "piper", but the gate is native+voice readiness only.
                neuralTts = notReady(
                    "Neural TTS",
                    "Piper native library unavailable; preference alone does not count"
                )
            )
        )

        assertThat(result.supportedMode)
            .isNotEqualTo(OfflineVoiceProfile.SupportedMode.FullyOffline)
        assertThat(result.supportedMode)
            .isEqualTo(OfflineVoiceProfile.SupportedMode.LocalSTTAndLLMSystemTts)
        assertThat(result.neuralTts.ready).isFalse()
    }

    @Test
    fun `missing local LLM reports NotReady even when STT and TTS are ready`() {
        val result = OfflineVoiceProfile.evaluate(
            OfflineVoiceProfile.Inputs(
                localStt = ready("Local STT", "Whisper native + model ready"),
                localLlm = notReady("Local LLM", "No embedded model downloaded"),
                systemTts = ready("System TTS", "Android TTS engine present"),
                neuralTts = notReady("Neural TTS", "Piper native library unavailable")
            )
        )

        assertThat(result.supportedMode).isEqualTo(OfflineVoiceProfile.SupportedMode.NotReady)
        assertThat(result.localLlm.ready).isFalse()
    }

    @Test
    fun `component statuses are echoed with ready label and reason`() {
        val inputs = OfflineVoiceProfile.Inputs(
            localStt = ready("Local STT", "ok"),
            localLlm = ready("Local LLM", "ok"),
            systemTts = notReady("System TTS", "No TTS engine installed"),
            neuralTts = notReady("Neural TTS", "Piper voice missing")
        )

        val result = OfflineVoiceProfile.evaluate(inputs)

        assertThat(result.localStt).isEqualTo(inputs.localStt)
        assertThat(result.localLlm).isEqualTo(inputs.localLlm)
        assertThat(result.systemTts).isEqualTo(inputs.systemTts)
        assertThat(result.neuralTts).isEqualTo(inputs.neuralTts)
        assertThat(result.supportedMode).isEqualTo(OfflineVoiceProfile.SupportedMode.NotReady)
    }

    private fun ready(label: String, reason: String) =
        OfflineVoiceProfile.ComponentStatus(ready = true, label = label, reason = reason)

    private fun notReady(label: String, reason: String) =
        OfflineVoiceProfile.ComponentStatus(ready = false, label = label, reason = reason)
}
