package com.opendash.app.voice.alert

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Owns the wake-word-free listen loop while an in-app alarm/timer is
 * ringing. Policy ([AlertSessionPolicy]) decides when to listen/resume;
 * this class owns the coroutine and side-effect hooks.
 *
 * [captureUtterance] must be a **bounded** one-shot STT capture (silence
 * end or provider timeout) — never an open-ended mic session.
 */
class AlertSessionCoordinator(
    private val scope: CoroutineScope,
    private val policy: AlertSessionPolicy = AlertSessionPolicy(),
    private val inventory: RingingAlertInventory,
    private val router: AlertCommandRouter = AlertCommandRouter(),
    private val executor: AlertCommandExecutor,
    private val captureUtterance: suspend () -> String?,
    private val resumeWakeWord: () -> Unit,
    private val onOutcome: (AlertCommandOutcome) -> Unit = {},
) {
    private val mutex = Mutex()
    private var sessionJob: Job? = null

    fun notifyRingingChanged() {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            mutex.withLock {
                val hasRinging = runCatching { inventory.hasRinging() }.getOrDefault(false)
                applyActions(policy.onRingingChanged(hasRinging))
            }
        }
    }

    private suspend fun applyActions(actions: List<AlertSessionAction>) {
        for (action in actions) {
            when (action) {
                AlertSessionAction.StartListening -> runListenCycle()
                AlertSessionAction.ResumeWakeWord -> {
                    Timber.d("Alert session returning to wake-word listening")
                    resumeWakeWord()
                }
            }
        }
    }

    private suspend fun runListenCycle() {
        Timber.d("Alert session starting bounded listen")
        val text = runCatching { captureUtterance() }
            .onFailure { Timber.w(it, "Alert session capture failed") }
            .getOrNull()

        val ringing = runCatching { inventory.snapshot() }.getOrDefault(emptyList())
        val outcome = if (text.isNullOrBlank()) {
            AlertCommandOutcome.NoMatch(stillRinging = ringing.isNotEmpty())
        } else {
            val command = router.route(text, ringing)
            Timber.d("Alert session routed '$text' -> $command")
            executor.execute(command)
        }
        runCatching { onOutcome(outcome) }
            .onFailure { Timber.w(it, "Alert session measurement hook failed") }

        applyActions(policy.onCommandResolved(outcome))
    }
}
