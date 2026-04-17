package com.opensmarthome.speaker.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Root-free UI control surface for Phase 15. This PR (P15.1) establishes the
 * service skeleton only — follow-up PRs (P15.2 – P15.5) add the
 * `read_active_screen`, `tap_by_text`, `scroll_screen`, and `type_text` tools
 * on top of the reference exposed via [A11yServiceHolder].
 *
 * The user must enable the service in Settings > Accessibility. The companion
 * [A11yServiceHolder] is notified on connect / unbind so tool executors can
 * call back into the live service without depending on Android context.
 */
@AndroidEntryPoint
class OpenSmartSpeakerA11yService : AccessibilityService() {

    @Inject
    lateinit var holder: A11yServiceHolder

    @Volatile
    private var lastForegroundPackage: String? = null

    /**
     * Returns the most recently observed foreground package name. Callers may
     * also observe [A11yServiceHolder.currentPackage] as a StateFlow.
     */
    fun currentForegroundPackage(): String? = lastForegroundPackage

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            flags = AccessibilityServiceInfo.DEFAULT or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
        holder.attach(this)
        Timber.d("OpenSmartSpeakerA11yService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != lastForegroundPackage) {
            lastForegroundPackage = pkg
            holder.updateCurrentPackage(pkg)
            Timber.v("a11y event: type=%d pkg=%s", event.eventType, pkg)
        }
    }

    override fun onInterrupt() {
        // No-op: skeleton service does not coordinate long-running feedback.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        holder.detach(this)
        lastForegroundPackage = null
        Timber.d("OpenSmartSpeakerA11yService unbound")
        return super.onUnbind(intent)
    }
}
