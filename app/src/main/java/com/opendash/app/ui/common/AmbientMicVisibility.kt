package com.opendash.app.ui.common

import com.opendash.app.voice.wakeword.WakeWordHealth

/**
 * Whether the ambient shell should expose the mic Talk action.
 * Hide only while hotword is healthy (listening) or intentionally paused for STT.
 */
object AmbientMicVisibility {
    fun shouldShowTalkAction(hotwordEnabled: Boolean, health: WakeWordHealth): Boolean {
        if (!hotwordEnabled) return true
        return when (health) {
            is WakeWordHealth.Listening -> false
            is WakeWordHealth.Paused -> false
            is WakeWordHealth.Unavailable -> true
            is WakeWordHealth.Failed -> true
        }
    }
}
