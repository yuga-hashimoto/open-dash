package com.opensmarthome.speaker.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    /**
     * Walks [rootInActiveWindow] as a BFS tree and returns a flat list of
     * nodes with non-blank text or content-description. Caps traversal at
     * [MAX_DEPTH] and [MAX_NODES] for safety. Recycles every visited node
     * explicitly — even on API 33+ where the platform recommends against it,
     * we keep the manual recycle for broad compatibility with older devices
     * this app still targets (min SDK 28).
     */
    fun dumpActiveWindow(): List<NodeSummary> {
        val root = rootInActiveWindow ?: return emptyList()
        return walkTree(root)
    }

    companion object {
        const val MAX_DEPTH: Int = 8
        const val MAX_NODES: Int = 200

        /**
         * Pure BFS walker extracted for unit testing. Consumes a root
         * [AccessibilityNodeInfo] and returns a flat list of [NodeSummary].
         *
         * Skips nodes where both `text` and `contentDescription` are null or
         * blank. Every visited node is recycled to avoid leaking native
         * references on older API levels.
         */
        fun walkTree(root: AccessibilityNodeInfo): List<NodeSummary> {
            val out = mutableListOf<NodeSummary>()
            // Queue entries carry (node, depth). Nodes are owned by the
            // queue and recycled after being processed.
            val queue: ArrayDeque<Pair<AccessibilityNodeInfo, Int>> = ArrayDeque()
            queue.addLast(root to 0)

            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val (node, depth) = queue.removeFirst()
                visited++

                try {
                    val text = node.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
                    val desc = node.contentDescription?.toString()?.trim()
                        .takeUnless { it.isNullOrBlank() }

                    if (text != null || desc != null) {
                        val bounds = Rect().also { node.getBoundsInScreen(it) }
                        out.add(
                            NodeSummary(
                                text = text,
                                role = roleFor(node),
                                contentDescription = desc,
                                clickable = node.isClickable,
                                bounds = bounds
                            )
                        )
                    }

                    if (depth < MAX_DEPTH) {
                        for (i in 0 until node.childCount) {
                            val child = node.getChild(i) ?: continue
                            queue.addLast(child to depth + 1)
                        }
                    }
                } finally {
                    // Don't recycle the root — caller owns it via
                    // rootInActiveWindow and the platform manages its
                    // lifecycle. We still recycle discovered children.
                    if (node !== root) {
                        @Suppress("DEPRECATION")
                        runCatching { node.recycle() }
                    }
                }
            }

            // Drain the remaining queue if we hit MAX_NODES without
            // processing them, so we don't leak those nodes either.
            while (queue.isNotEmpty()) {
                val (leftover, _) = queue.removeFirst()
                if (leftover !== root) {
                    @Suppress("DEPRECATION")
                    runCatching { leftover.recycle() }
                }
            }

            return out
        }

        private fun roleFor(node: AccessibilityNodeInfo): String {
            val className = node.className?.toString().orEmpty()
            val simple = className.substringAfterLast('.')
            return when {
                simple.isBlank() -> "view"
                simple.equals("EditText", ignoreCase = true) -> "edit-text"
                simple.contains("Button", ignoreCase = true) -> "button"
                simple.contains("ImageView", ignoreCase = true) -> "image"
                simple.contains("TextView", ignoreCase = true) -> "text"
                simple.contains("CheckBox", ignoreCase = true) -> "checkbox"
                simple.contains("Switch", ignoreCase = true) -> "switch"
                else -> simple.lowercase()
            }
        }
    }
}

/**
 * Flat summary of a single [AccessibilityNodeInfo] produced by
 * [OpenSmartSpeakerA11yService.dumpActiveWindow].
 */
data class NodeSummary(
    val text: String?,
    val role: String,
    val contentDescription: String?,
    val clickable: Boolean,
    val bounds: Rect
)
