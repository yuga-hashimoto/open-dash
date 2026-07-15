package com.opendash.app.voice.wakeword

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped wake-word health visible to UI (ModeScaffold) and VoiceService.
 * VoiceService is the sole writer; ambient shell reads [health] to decide mic fallback.
 */
@Singleton
class WakeWordHealthStore @Inject constructor() {
    private val _health = MutableStateFlow<WakeWordHealth>(
        WakeWordHealth.Unavailable("not started")
    )
    val health: StateFlow<WakeWordHealth> = _health.asStateFlow()

    fun report(health: WakeWordHealth) {
        _health.value = health
    }
}
