package com.opendash.app.voice.alert

enum class AlertKind {
    TIMER,
    ALARM,
}

data class RingingAlert(
    val id: String,
    val kind: AlertKind,
    val label: String = "",
)
