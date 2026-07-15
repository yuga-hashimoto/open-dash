package com.opendash.app.voice.alert

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AlertSessionPolicyTest {

    private val policy = AlertSessionPolicy(maxListenAttempts = 2)

    @Test
    fun `starts listening only when a ringing alert appears while idle`() {
        val actions = policy.onRingingChanged(hasRinging = true)
        assertThat(actions).containsExactly(AlertSessionAction.StartListening)
        assertThat(policy.state).isEqualTo(AlertSessionState.Listening)
    }

    @Test
    fun `does not start listening when nothing is ringing`() {
        val actions = policy.onRingingChanged(hasRinging = false)
        assertThat(actions).isEmpty()
        assertThat(policy.state).isEqualTo(AlertSessionState.Idle)
    }

    @Test
    fun `does not start a second session while already listening`() {
        policy.onRingingChanged(hasRinging = true)
        val actions = policy.onRingingChanged(hasRinging = true)
        assertThat(actions).isEmpty()
        assertThat(policy.state).isEqualTo(AlertSessionState.Listening)
    }

    @Test
    fun `successful stop returns to wake word`() {
        policy.onRingingChanged(hasRinging = true)
        val actions = policy.onCommandResolved(AlertCommandOutcome.Handled)
        assertThat(actions).containsExactly(AlertSessionAction.ResumeWakeWord)
        assertThat(policy.state).isEqualTo(AlertSessionState.Idle)
    }

    @Test
    fun `no-match retries listening while still ringing up to the budget`() {
        policy.onRingingChanged(hasRinging = true)

        val first = policy.onCommandResolved(AlertCommandOutcome.NoMatch(stillRinging = true))
        assertThat(first).containsExactly(AlertSessionAction.StartListening)
        assertThat(policy.state).isEqualTo(AlertSessionState.Listening)

        val second = policy.onCommandResolved(AlertCommandOutcome.NoMatch(stillRinging = true))
        assertThat(second).containsExactly(AlertSessionAction.ResumeWakeWord)
        assertThat(policy.state).isEqualTo(AlertSessionState.Idle)
    }

    @Test
    fun `no-match with no remaining ringing alerts resumes wake word`() {
        policy.onRingingChanged(hasRinging = true)
        val actions = policy.onCommandResolved(AlertCommandOutcome.NoMatch(stillRinging = false))
        assertThat(actions).containsExactly(AlertSessionAction.ResumeWakeWord)
        assertThat(policy.state).isEqualTo(AlertSessionState.Idle)
    }

    @Test
    fun `timeout or error returns to wake word`() {
        policy.onRingingChanged(hasRinging = true)
        val actions = policy.onCommandResolved(AlertCommandOutcome.Failed)
        assertThat(actions).containsExactly(AlertSessionAction.ResumeWakeWord)
        assertThat(policy.state).isEqualTo(AlertSessionState.Idle)
    }

    @Test
    fun `ringing ending while listening resumes wake word`() {
        policy.onRingingChanged(hasRinging = true)
        val actions = policy.onRingingChanged(hasRinging = false)
        assertThat(actions).containsExactly(AlertSessionAction.ResumeWakeWord)
        assertThat(policy.state).isEqualTo(AlertSessionState.Idle)
    }

    @Test
    fun `clarification is handled then listening can continue if still ringing`() {
        policy.onRingingChanged(hasRinging = true)
        val actions = policy.onCommandResolved(
            AlertCommandOutcome.NeedsClarification(stillRinging = true)
        )
        // Speak is done by the coordinator; policy only decides listen/resume.
        assertThat(actions).containsExactly(AlertSessionAction.StartListening)
        assertThat(policy.state).isEqualTo(AlertSessionState.Listening)
    }
}
