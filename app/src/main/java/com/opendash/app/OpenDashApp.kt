package com.opendash.app

import android.app.Application
import com.opendash.app.assistant.provider.ProviderManager
import com.opendash.app.util.LocaleManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class OpenDashApp : Application() {

    @Inject lateinit var localeManager: LocaleManager
    @Inject lateinit var providerManager: ProviderManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // Re-apply saved UI locale before any UI surface spins up — the
        // LocaleManager is an injected Singleton so Hilt's graph is
        // already live by the time Application.onCreate runs.
        appScope.launch { localeManager.applySaved() }
        // Idempotent: MainActivity may also call initialize(); the second
        // call is a no-op. Boot-started VoiceService can await readiness
        // without requiring an activity.
        providerManager.initialize()
    }
}
