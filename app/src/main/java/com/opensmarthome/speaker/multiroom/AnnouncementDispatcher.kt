package com.opensmarthome.speaker.multiroom

import com.opensmarthome.speaker.assistant.model.AssistantMessage
import com.opensmarthome.speaker.assistant.session.ConversationHistoryManager
import com.opensmarthome.speaker.tool.system.TimerManager
import com.opensmarthome.speaker.voice.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Routes a parsed [AnnouncementEnvelope] to its effect. Today: `tts_broadcast`
 * speaks, `heartbeat` is ack'd, `session_handoff` seeds local conversation
 * history, `start_timer` fans a peer timer into the local [TimerManager].
 * Unknown types are logged and ignored so receivers tolerate newer senders
 * gracefully.
 *
 * Keeping this headless (no Context) makes the dispatcher unit-testable
 * without Android plumbing; TTS, history, and timer are all abstracted.
 *
 * [historyProvider] / [timerManagerProvider] are lambdas so the dispatcher
 * doesn't pin its owner's lifecycle. Either can return null in tests where
 * the corresponding effect isn't exercised.
 */
class AnnouncementDispatcher(
    private val tts: TextToSpeech,
    private val historyProvider: () -> ConversationHistoryManager? = { null },
    private val timerManagerProvider: () -> TimerManager? = { null }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Maximum timer duration a cross-speaker `start_timer` envelope can
     * request. 24 hours — anything larger is almost certainly a bad payload
     * (overflow, unit confusion) and would pin a timer on every peer for
     * days. The local `set_timer` tool accepts any value; this cap exists
     * specifically because the source of truth is another device.
     */
    private val maxSeconds = 86_400

    /**
     * Handle an incoming envelope. Returns a short tag describing the outcome
     * so the caller can log or update a counter.
     */
    fun dispatch(envelope: AnnouncementEnvelope): DispatchOutcome {
        return when (envelope.type) {
            AnnouncementType.TTS_BROADCAST -> handleTtsBroadcast(envelope)
            AnnouncementType.HEARTBEAT -> DispatchOutcome.AcknowledgedHeartbeat
            AnnouncementType.SESSION_HANDOFF -> handleSessionHandoff(envelope)
            AnnouncementType.START_TIMER -> handleStartTimer(envelope)
            else -> {
                Timber.d("Dispatcher: ignoring unhandled type '${envelope.type}' from ${envelope.from}")
                DispatchOutcome.Unhandled(envelope.type)
            }
        }
    }

    /**
     * Parse + validate a `start_timer` envelope and kick off the local
     * [TimerManager.setTimer]. Returns synchronously with a [DispatchOutcome];
     * the actual setTimer call runs on the dispatcher's background scope
     * (same pattern as tts_broadcast).
     */
    private fun handleStartTimer(envelope: AnnouncementEnvelope): DispatchOutcome {
        val seconds = when (val raw = envelope.payload["seconds"]) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: return DispatchOutcome.Rejected("start_timer invalid seconds")
            else -> return DispatchOutcome.Rejected("start_timer invalid seconds")
        }
        if (seconds <= 0 || seconds > maxSeconds) {
            return DispatchOutcome.Rejected("start_timer invalid seconds")
        }
        val label = (envelope.payload["label"] as? String)?.takeIf { it.isNotBlank() }
        val tm = timerManagerProvider()
            ?: return DispatchOutcome.Rejected("no timer manager available")
        scope.launch {
            runCatching { tm.setTimer(seconds, label ?: "") }
                .onFailure { Timber.w(it, "setTimer from announcement failed") }
        }
        return DispatchOutcome.TimerStarted(seconds, label)
    }

    private fun handleTtsBroadcast(envelope: AnnouncementEnvelope): DispatchOutcome {
        val text = envelope.payload["text"] as? String
        if (text.isNullOrBlank()) {
            return DispatchOutcome.Rejected("tts_broadcast missing text")
        }
        scope.launch {
            runCatching { tts.speak(text) }
                .onFailure { Timber.w(it, "TTS speak from announcement failed") }
        }
        return DispatchOutcome.Spoke(text)
    }

    /**
     * Handle session_handoff. For `mode=conversation`, replace the local
     * history with the provided messages (replace, not append — the user
     * said "move this", so the target should pick up where the source
     * left off, not stack on top of its own unrelated session). For
     * `mode=media`, return Unhandled with a TODO; real media transport
     * is deferred (see TODO below).
     */
    private fun handleSessionHandoff(envelope: AnnouncementEnvelope): DispatchOutcome {
        val mode = envelope.payload["mode"] as? String
        return when (mode) {
            AnnouncementBroadcaster.MODE_CONVERSATION -> seedConversation(envelope)
            AnnouncementBroadcaster.MODE_MEDIA -> {
                // TODO(P17.future): media handoff — requires querying the
                // active MediaSession, pausing it locally, and instructing
                // the target to resume from the same playback position.
                // Out of scope for P17.5 (conversation-only).
                Timber.w("session_handoff media not yet wired (from=${envelope.from})")
                DispatchOutcome.Unhandled("session_handoff media not yet wired")
            }
            else -> DispatchOutcome.Rejected("session_handoff missing or unknown mode")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun seedConversation(envelope: AnnouncementEnvelope): DispatchOutcome {
        val rawMessages = envelope.payload["messages"] as? List<*>
            ?: return DispatchOutcome.Rejected("session_handoff missing messages")
        val parsed = rawMessages.mapNotNull { entry ->
            val m = entry as? Map<String, Any?> ?: return@mapNotNull null
            val role = m["role"] as? String ?: return@mapNotNull null
            val content = m["content"] as? String ?: return@mapNotNull null
            when (role) {
                "user" -> AssistantMessage.User(content = content)
                "assistant" -> AssistantMessage.Assistant(content = content)
                "system" -> AssistantMessage.System(content = content)
                else -> null
            }
        }
        val history = historyProvider()
        if (history == null) {
            Timber.w("session_handoff received but no history manager wired (from=${envelope.from})")
            return DispatchOutcome.Rejected("no history manager available")
        }
        // Replace (not append): the handoff moves the conversation — the
        // target shouldn't blend in whatever it was doing before.
        history.clear()
        parsed.forEach { history.add(it) }
        return DispatchOutcome.HandoffSeeded(parsed.size)
    }

    sealed interface DispatchOutcome {
        data class Spoke(val text: String) : DispatchOutcome
        data object AcknowledgedHeartbeat : DispatchOutcome
        data class Unhandled(val type: String) : DispatchOutcome
        data class Rejected(val reason: String) : DispatchOutcome

        /** session_handoff (mode=conversation) seeded [count] messages into local history. */
        data class HandoffSeeded(val count: Int) : DispatchOutcome

        /**
         * start_timer accepted: a peer requested that every speaker start a
         * timer for [seconds] with optional [label]. The local [TimerManager]
         * has been invoked asynchronously.
         */
        data class TimerStarted(val seconds: Int, val label: String?) : DispatchOutcome
    }
}
