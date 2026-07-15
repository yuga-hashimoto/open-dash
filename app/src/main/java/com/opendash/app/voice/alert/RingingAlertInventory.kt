package com.opendash.app.voice.alert

import com.opendash.app.tool.system.TimerManager
import com.opendash.app.voice.alarm.AlarmRingtoneController

/**
 * Point-in-time snapshot of every currently-ringing in-app alert
 * (firing timers + live alarm tones). Pure query over existing managers.
 */
class RingingAlertInventory(
    private val alarmRingtoneController: AlarmRingtoneController,
    private val timerManager: TimerManager,
) {
    suspend fun snapshot(): List<RingingAlert> {
        val alarms = alarmRingtoneController.ringingIds().map { id ->
            RingingAlert(id = id, kind = AlertKind.ALARM)
        }
        val timers = timerManager.getActiveTimers()
            .filter { it.isFiring }
            .map { info ->
                RingingAlert(id = info.id, kind = AlertKind.TIMER, label = info.label)
            }
        return alarms + timers
    }

    suspend fun hasRinging(): Boolean = snapshot().isNotEmpty()
}
