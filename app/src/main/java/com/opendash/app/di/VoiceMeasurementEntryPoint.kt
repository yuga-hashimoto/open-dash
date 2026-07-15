package com.opendash.app.di

import com.opendash.app.voice.diagnostics.VoiceMeasurementRecorder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Allows Compose-only diagnostics to observe the service-owned recorder. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface VoiceMeasurementEntryPoint {
    fun voiceMeasurementRecorder(): VoiceMeasurementRecorder
}
