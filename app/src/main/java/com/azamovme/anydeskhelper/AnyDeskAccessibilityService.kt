package com.azamovme.anydeskhelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.bugsnag.android.Bugsnag

class AnyDeskAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "AnyDeskHelper"
        var isServiceEnabled = false

        private const val ANYDESK_PACKAGE = "com.anydesk.anydeskandroid"
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"

        // AnyDesk dialog IDs
        private const val DIALOG_TITLE_ID = "com.anydesk.anydeskandroid:id/dialog_accept_title_text"
        private const val ACCEPT_BUTTON_ID = "android:id/button1"

        // System UI Share Screen dialog IDs
        private const val SHARE_SCREEN_DIALOG_ID =
            "com.android.systemui:id/screen_share_permission_dialog"
        private const val SHARE_SCREEN_SPINNER_ID =
            "com.android.systemui:id/screen_share_mode_options"
        private const val SHARE_SCREEN_NEXT_BUTTON_ID = "android:id/button1"
        private const val SHARE_CHOOSER_TEXT_ID = "android:id/text1"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var isProcessing = false
    private var lastProcessTime = 0L
    private val MIN_PROCESS_INTERVAL = 500L

    // State management
    private var anydeskAccepted = false
    private var currentStep =
        0 // 0: wait for AnyDesk, 1: wait for share dialog, 2: wait for chooser, 3: wait for share button

    // Polling mechanism
    private var isPollingActive = false
    private val pollingRunnable = Runnable { pollForWindows() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "🟢 Accessibility Service Connected")
        isServiceEnabled = true

        // Initialize Bugsnag
        try {
            Bugsnag.start(this)
            notifyBugsnag(
                "Service Connected", mapOf(
                    "state" to "CONNECTED",
                    "step" to currentStep.toString(),
                    "anydeskAccepted" to anydeskAccepted.toString()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Bugsnag initialization failed", e)
        }

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED

            packageNames = arrayOf(ANYDESK_PACKAGE, SYSTEMUI_PACKAGE)
            notificationTimeout = 100
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }

        this.serviceInfo = info
        startPolling()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString()
        if (packageName != ANYDESK_PACKAGE && packageName != SYSTEMUI_PACKAGE) {
            return
        }

        // Log event for debugging
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.d(TAG, "📱 Window state changed - Package: $packageName")
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                Log.d(TAG, "📄 Window content changed - Package: $packageName")
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                Log.d(TAG, "🖱️ View clicked - Package: $packageName")
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                Log.d(TAG, "🎯 View focused - Package: $packageName")
            }
        }

        // Process event immediately
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            processCurrentWindow()
        }, 200)
    }

    /**
     * Process current window based on state
     */
    private fun processCurrentWindow() {
        if (isProcessing) {
            return
        }

        isProcessing = true
        coroutineScope.launch {
            try {
                val rootNode = rootInActiveWindow
                if (rootNode == null) {
                    Log.d(TAG, "⚠️ Root node is null")
                    isProcessing = false
                    return@launch
                }

                val packageName = rootNode.packageName?.toString()
                Log.d(
                    TAG,
                    "🔍 Processing - Package: $packageName, Step: $currentStep, AnyDeskAccepted: $anydeskAccepted"
                )

                when {
                    !anydeskAccepted && packageName == ANYDESK_PACKAGE -> {
                        handleAnyDeskDialog(rootNode)
                    }

                    anydeskAccepted && packageName == SYSTEMUI_PACKAGE -> {
                        handleSystemUIFlow(rootNode)
                    }

                    else -> {
                        Log.d(
                            TAG,
                            "📭 Not processing - Package: $packageName doesn't match current state"
                        )
                    }
                }

                safeRecycle(rootNode)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error processing window", e)
                notifyBugsnag(
                    "Window Processing Error", mapOf(
                        "error" to e.message.toString(),
                        "step" to currentStep.toString(),
                        "anydeskAccepted" to anydeskAccepted.toString()
                    ), e
                )
            } finally {
                isProcessing = false
            }
        }
    }

    /**
     * Handle AnyDesk incoming connection dialog
     */
    private fun handleAnyDeskDialog(rootNode: AccessibilityNodeInfo) {
        try {
            Log.d(TAG, "🔍 Looking for AnyDesk incoming dialog...")

            val titleNode = findNodeById(rootNode, DIALOG_TITLE_ID)
            val titleText = titleNode?.text?.toString()?.trim()
            val isIncomingDialog = titleText?.contains("Incoming", ignoreCase = true) == true

            safeRecycle(titleNode)

            if (isIncomingDialog) {
                Log.d(TAG, "✅ Found AnyDesk incoming dialog")

                // Get current UI hierarchy for debugging
                val hierarchy = getCurrentHierarchy(rootNode)

                // Notify Bugsnag about AnyDesk dialog detection
                notifyBugsnag(
                    "AnyDesk Dialog Detected", mapOf(
                        "title" to titleText,
                        "step" to currentStep.toString(),
                        "action" to "STARTING_AUTO_ACCEPT",
                        "hierarchy" to hierarchy
                    )
                )

                val acceptButton = findAcceptButton(rootNode)
                if (acceptButton != null && acceptButton.isClickable) {
                    Log.d(TAG, "🎯 Clicking ACCEPT button...")
                    if (performClick(acceptButton)) {
                        Log.d(TAG, "✅ ACCEPT button clicked!")
                        anydeskAccepted = true
                        currentStep = 1
                        showToastNotification("✅ Connection accepted")

                        // Notify Bugsnag about successful accept
                        notifyBugsnag(
                            "AnyDesk Accepted", mapOf(
                                "step" to currentStep.toString(),
                                "anydeskAccepted" to anydeskAccepted.toString(),
                                "action" to "ACCEPT_CLICKED",
                                "hierarchy" to getCurrentHierarchy(rootNode)
                            )
                        )

                        handler.postDelayed({
                            Log.d(TAG, "⏳ Now looking for share screen dialog...")
                        }, 800)
                    }
                    safeRecycle(acceptButton)
                } else {
                    Log.w(TAG, "⚠️ ACCEPT button not found or not clickable")
                    notifyBugsnag(
                        "Accept Button Not Found", mapOf(
                            "step" to currentStep.toString(),
                            "title" to titleText,
                            "hierarchy" to getCurrentHierarchy(rootNode)
                        )
                    )
                }
            } else {
                Log.d(TAG, "📭 Not an incoming dialog or title not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling AnyDesk dialog", e)
            notifyBugsnag(
                "AnyDesk Dialog Error", mapOf(
                    "error" to e.message.toString(),
                    "step" to currentStep.toString(),
                    "hierarchy" to getCurrentHierarchy(rootNode)
                ), e
            )
        }
    }

    /**
     * Handle SystemUI share screen flow
     */
    private fun handleSystemUIFlow(rootNode: AccessibilityNodeInfo) {
        try {
            Log.d(TAG, "🔍 Handling SystemUI flow - Step: $currentStep")

            when (currentStep) {
                1 -> handleShareScreenDialog(rootNode)
                2 -> handleShareChooser(rootNode)
                3 -> handleShareButton(rootNode)
                else -> {
                    if (anydeskAccepted) {
                        checkForShareScreenDialog(rootNode)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling SystemUI flow", e)
            notifyBugsnag(
                "SystemUI Flow Error", mapOf(
                    "error" to e.message.toString(),
                    "step" to currentStep.toString(),
                    "hierarchy" to getCurrentHierarchy(rootNode)
                ), e
            )
        }
    }

    /**
     * Check for and handle share screen dialog (Step 1)
     */
    private fun handleShareScreenDialog(rootNode: AccessibilityNodeInfo) {
        try {
            Log.d(TAG, "🔍 Looking for share screen dialog...")

            val dialog = findNodeById(rootNode, SHARE_SCREEN_DIALOG_ID)
            if (dialog != null) {
                Log.d(TAG, "✅ Found share screen dialog")
                safeRecycle(dialog)

                // Notify Bugsnag about share screen dialog
                notifyBugsnag(
                    "Share Screen Dialog Detected", mapOf(
                        "step" to currentStep.toString(),
                        "action" to "FOUND_SHARE_DIALOG",
                        "hierarchy" to getCurrentHierarchy(rootNode)
                    )
                )

                val spinner = findNodeById(rootNode, SHARE_SCREEN_SPINNER_ID)
                if (spinner != null && spinner.isClickable) {
                    Log.d(TAG, "🎯 Clicking share mode spinner...")
                    if (performClick(spinner)) {
                        Log.d(TAG, "✅ Spinner clicked!")
                        currentStep = 2

                        // Notify Bugsnag about spinner click
                        notifyBugsnag(
                            "Spinner Clicked", mapOf(
                                "step" to currentStep.toString(),
                                "action" to "SPINNER_CLICKED",
                                "hierarchy" to getCurrentHierarchy(rootNode)
                            )
                        )

                        handler.postDelayed({
                            Log.d(TAG, "⏳ Now looking for share chooser...")
                        }, 500)
                    }
                    safeRecycle(spinner)
                } else {
                    Log.w(TAG, "⚠️ Spinner not found or not clickable")
                    notifyBugsnag(
                        "Spinner Not Found", mapOf(
                            "step" to currentStep.toString(),
                            "hierarchy" to getCurrentHierarchy(rootNode)
                        )
                    )
                }
            } else {
                Log.d(TAG, "📭 Share screen dialog not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling share screen dialog", e)
            notifyBugsnag(
                "Share Dialog Error", mapOf(
                    "error" to e.message.toString(),
                    "step" to currentStep.toString(),
                    "hierarchy" to getCurrentHierarchy(rootNode)
                ), e
            )
        }
    }

    /**
     * Check if we're already on share screen dialog when step is 0
     */
    private fun checkForShareScreenDialog(rootNode: AccessibilityNodeInfo) {
        try {
            val dialog = findNodeById(rootNode, SHARE_SCREEN_DIALOG_ID)
            if (dialog != null) {
                Log.d(TAG, "📝 Found share screen dialog when step was 0, moving to step 1")
                safeRecycle(dialog)
                currentStep = 1
                handleShareScreenDialog(rootNode)
            } else {
                safeRecycle(dialog)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking for share screen dialog", e)
            notifyBugsnag(
                "Check Share Dialog Error", mapOf(
                    "error" to e.message.toString(),
                    "hierarchy" to getCurrentHierarchy(rootNode)
                ), e
            )
        }
    }

    /**
     * Handle share chooser dropdown (Step 2)
     */
    private fun handleShareChooser(rootNode: AccessibilityNodeInfo) {
        try {
            Log.d(TAG, "🔍 Looking for share chooser...")

            val entireScreenNodes = rootNode.findAccessibilityNodeInfosByText("Share entire screen")
            if (entireScreenNodes.isNotEmpty()) {
                Log.d(TAG, "✅ Found 'Share entire screen' text")

                // Notify Bugsnag about chooser detection
                notifyBugsnag(
                    "Share Chooser Detected", mapOf(
                        "step" to currentStep.toString(),
                        "action" to "FOUND_SHARE_CHOOSER",
                        "optionText" to "Share entire screen",
                        "hierarchy" to getCurrentHierarchy(rootNode)
                    )
                )

                for (node in entireScreenNodes) {
                    var clickableNode: AccessibilityNodeInfo? = null

                    if (node.isClickable) {
                        clickableNode = node
                    } else if (node.parent != null && node.parent.isClickable) {
                        clickableNode = node.parent
                    }

                    if (clickableNode != null) {
                        Log.d(TAG, "🎯 Clicking 'Share entire screen'...")
                        if (performClick(clickableNode)) {
                            Log.d(TAG, "✅ 'Share entire screen' selected!")
                            currentStep = 3

                            // Notify Bugsnag about option selection
                            notifyBugsnag(
                                "Share Entire Screen Selected", mapOf(
                                    "step" to currentStep.toString(),
                                    "action" to "ENTIRE_SCREEN_SELECTED",
                                    "hierarchy" to getCurrentHierarchy(rootNode)
                                )
                            )

                            handler.postDelayed({
                                Log.d(TAG, "⏳ Now looking for share button...")
                            }, 500)

                            if (clickableNode != node) {
                                safeRecycle(clickableNode)
                            }
                            break
                        }

                        if (clickableNode != node) {
                            safeRecycle(clickableNode)
                        }
                    }
                }

                entireScreenNodes.forEach { safeRecycle(it) }
            } else {
                Log.d(TAG, "📭 'Share entire screen' text not found")

                val textNodes = rootNode.findAccessibilityNodeInfosByViewId(SHARE_CHOOSER_TEXT_ID)
                for (node in textNodes) {
                    val text = node.text?.toString()?.trim()
                    Log.d(TAG, "Found text node: $text")

                    if (text?.contains("entire screen", ignoreCase = true) == true) {
                        Log.d(TAG, "✅ Found entire screen option via ID")

                        notifyBugsnag(
                            "Entire Screen Option Found", mapOf(
                                "step" to currentStep.toString(),
                                "optionText" to text,
                                "foundBy" to "ID",
                                "hierarchy" to getCurrentHierarchy(rootNode)
                            )
                        )

                        val parent = node.parent
                        if (parent != null && parent.isClickable) {
                            Log.d(TAG, "🎯 Clicking via parent...")
                            if (performClick(parent)) {
                                Log.d(TAG, "✅ Option selected!")
                                currentStep = 3

                                notifyBugsnag(
                                    "Option Selected via Parent", mapOf(
                                        "step" to currentStep.toString(),
                                        "action" to "OPTION_SELECTED",
                                        "hierarchy" to getCurrentHierarchy(rootNode)
                                    )
                                )

                                handler.postDelayed({
                                    Log.d(TAG, "⏳ Now looking for share button...")
                                }, 500)

                                safeRecycle(parent)
                                break
                            }
                            safeRecycle(parent)
                        }
                    }
                }
                textNodes.forEach { safeRecycle(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling share chooser", e)
            notifyBugsnag(
                "Share Chooser Error", mapOf(
                    "error" to e.message.toString(),
                    "step" to currentStep.toString(),
                    "hierarchy" to getCurrentHierarchy(rootNode)
                ), e
            )
        }
    }

    /**
     * Handle share button (Step 3)
     */
    private fun handleShareButton(rootNode: AccessibilityNodeInfo) {
        try {
            Log.d(TAG, "🔍 Looking for share button...")

            val shareButton = findNodeById(rootNode, SHARE_SCREEN_NEXT_BUTTON_ID)
            if (shareButton != null && shareButton.isClickable) {
                val buttonText = shareButton.text?.toString()?.trim()
                Log.d(TAG, "✅ Found share button with text: $buttonText")

                if (buttonText.equals("Share screen", ignoreCase = true) ||
                    buttonText.equals("Next", ignoreCase = true)
                ) {

                    Log.d(TAG, "🎯 Clicking $buttonText button...")
                    if (performClick(shareButton)) {
                        Log.d(TAG, "✅ $buttonText button clicked!")

                        // Notify Bugsnag about successful share
                        notifyBugsnag(
                            "Screen Sharing Started", mapOf(
                                "buttonText" to buttonText.toString(),
                                "step" to currentStep.toString(),
                                "action" to "SHARE_STARTED",
                                "result" to "SUCCESS",
                                "hierarchy" to getCurrentHierarchy(rootNode)
                            )
                        )

                        resetStates()
                        showToastNotification("✅ Screen sharing started")
                    } else {
                        notifyBugsnag(
                            "Share Button Click Failed", mapOf(
                                "buttonText" to buttonText.toString(),
                                "step" to currentStep.toString().toString(),
                                "action" to "SHARE_CLICK_FAILED",
                                "hierarchy" to getCurrentHierarchy(rootNode)
                            )
                        )
                    }
                }
                safeRecycle(shareButton)
            } else {
                Log.w(TAG, "⚠️ Share button not found by ID")
                safeRecycle(shareButton)

                val shareButtons = rootNode.findAccessibilityNodeInfosByText("Share screen")
                val nextButtons = rootNode.findAccessibilityNodeInfosByText("Next")

                val allButtons = shareButtons + nextButtons
                for (button in allButtons) {
                    if (button.isClickable) {
                        val text = button.text?.toString()?.trim()
                        Log.d(TAG, "✅ Found button via text: $text")

                        if (performClick(button)) {
                            Log.d(TAG, "✅ Button clicked!")

                            notifyBugsnag(
                                "Screen Sharing Started via Text", mapOf(
                                    "buttonText" to text.toString(),
                                    "step" to currentStep.toString(),
                                    "action" to "SHARE_STARTED",
                                    "foundBy" to "TEXT",
                                    "hierarchy" to getCurrentHierarchy(rootNode)
                                )
                            )

                            resetStates()
                            showToastNotification("✅ Screen sharing started")
                            break
                        }
                    }
                }

                allButtons.forEach { safeRecycle(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling share button", e)
            notifyBugsnag(
                "Share Button Error", mapOf(
                    "error" to e.message.toString(),
                    "step" to currentStep.toString(),
                    "hierarchy" to getCurrentHierarchy(rootNode)
                ), e
            )
        }
    }

    /**
     * Find ACCEPT button in AnyDesk dialog
     */
    private fun findAcceptButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val byId = findNodeById(rootNode, ACCEPT_BUTTON_ID)
        if (byId != null && byId.isClickable) {
            val text = byId.text?.toString()?.trim()
            if (text.equals("ACCEPT", ignoreCase = true)) {
                return byId
            }
            safeRecycle(byId)
        }

        val buttons = rootNode.findAccessibilityNodeInfosByText("ACCEPT")
        for (button in buttons) {
            if (button.isClickable) {
                buttons.forEach { if (it != button) safeRecycle(it) }
                return button
            }
        }
        buttons.forEach { safeRecycle(it) }

        return null
    }

    /**
     * Find node by ID
     */
    private fun findNodeById(rootNode: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        return try {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                val node = nodes[0]
                for (i in 1 until nodes.size) {
                    safeRecycle(nodes[i])
                }
                return node
            }
            nodes.forEach { safeRecycle(it) }
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error finding node by ID: $id", e)
            notifyBugsnag(
                "Node Find Error", mapOf(
                    "id" to id,
                    "error" to e.message.toString(),
                    "hierarchy" to getCurrentHierarchy(rootNode)
                ), e
            )
            null
        }
    }

    /**
     * Get current UI hierarchy as string for debugging
     */
    private fun getCurrentHierarchy(rootNode: AccessibilityNodeInfo): String {
        return try {
            val stringBuilder = StringBuilder()
            dumpNodeHierarchy(rootNode, stringBuilder, 0, 3) // Limit depth to 3 levels
            stringBuilder.toString()
        } catch (e: Exception) {
            "Error getting hierarchy: ${e.message}"
        }
    }

    /**
     * Recursively dump node hierarchy
     */
    private fun dumpNodeHierarchy(
        node: AccessibilityNodeInfo,
        builder: StringBuilder,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return

        val indent = "  ".repeat(depth)
        builder.append(indent)

        // Add node info
        builder.append("Node: ")
        builder.append("class=").append(node.className?.toString() ?: "null")
        builder.append(", text=").append(node.text?.toString()?.take(50) ?: "null")
        builder.append(", id=").append(node.viewIdResourceName ?: "null")
        builder.append(", clickable=").append(node.isClickable)
        builder.append(", enabled=").append(node.isEnabled)
        builder.append(", visible=").append(node.isVisibleToUser)

        // Add bounds if available
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        builder.append(", bounds=[").append(bounds.left).append(",").append(bounds.top)
            .append("][").append(bounds.right).append(",").append(bounds.bottom).append("]")

        builder.append("\n")

        // Recursively dump children
        if (depth < maxDepth) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    dumpNodeHierarchy(child, builder, depth + 1, maxDepth)
                    child.recycle()
                } else {
                    builder.append("  ".repeat(depth + 1)).append("null child\n")
                }
            }
        }
    }

    /**
     * Perform click on node
     */
    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        return try {
            Log.d(TAG, "🖱️ Clicking: ${node.className}, text: ${node.text}")

            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "✓ Click performed")
                return true
            }

            if (node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) {
                Thread.sleep(50)
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.d(TAG, "✓ Click via focus")
                    return true
                }
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clicking node", e)
            // Get hierarchy from parent if possible
            val hierarchy = try {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    getCurrentHierarchy(rootNode)
                } else {
                    "No root node available"
                }
            } catch (ex: Exception) {
                "Error getting hierarchy: ${ex.message}"
            }

            notifyBugsnag(
                "Click Error", mapOf(
                    "nodeClass" to node.className?.toString().toString(),
                    "nodeText" to node.text?.toString().toString(),
                    "error" to e.message.toString(),
                    "hierarchy" to hierarchy
                ), e
            )
            false
        }
    }

    /**
     * Notify Bugsnag with custom data
     */
    private fun notifyBugsnag(
        message: String,
        metadata: Map<String, String> = emptyMap(),
        exception: Exception? = null
    ) {
        try {
            if (exception != null) {
                Bugsnag.notify(exception) { event ->
                    event.severity = com.bugsnag.android.Severity.ERROR
                    event.addMetadata("custom", "message", message)
                    metadata.forEach { (key, value) ->
                        event.addMetadata("custom", key, value)
                    }
                    event.addMetadata("state", "anydeskAccepted", anydeskAccepted.toString())
                    event.addMetadata("state", "currentStep", currentStep.toString())
                    event.addMetadata("state", "isProcessing", isProcessing)
                    return@notify true
                }
            } else {
                val runtimeException = RuntimeException(message)
                Bugsnag.notify(runtimeException) { event ->
                    event.severity = com.bugsnag.android.Severity.INFO
                    event.addMetadata("custom", "message", message)
                    metadata.forEach { (key, value) ->
                        event.addMetadata("custom", key, value)
                    }
                    event.addMetadata("state", "anydeskAccepted", anydeskAccepted.toString())
                    event.addMetadata("state", "currentStep", currentStep.toString())
                    event.addMetadata("state", "isProcessing", isProcessing.toString())
                    return@notify true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to notify Bugsnag", e)
        }
    }

    /**
     * Start polling for window changes
     */
    private fun startPolling() {
        if (!isPollingActive) {
            isPollingActive = true
            Log.d(TAG, "🔄 Starting polling...")
            notifyBugsnag("Polling Started", mapOf("action" to "POLLING_STARTED"))
            handler.postDelayed(pollingRunnable, 1000)
        }
    }

    /**
     * Stop polling
     */
    private fun stopPolling() {
        isPollingActive = false
        handler.removeCallbacks(pollingRunnable)
        Log.d(TAG, "🛑 Stopped polling")
        notifyBugsnag("Polling Stopped", mapOf("action" to "POLLING_STOPPED"))
    }

    /**
     * Poll for windows periodically
     */
    private fun pollForWindows() {
        if (!isPollingActive) return

        Log.d(TAG, "🔍 Polling for windows...")
        processCurrentWindow()

        handler.postDelayed(pollingRunnable, 1000)
    }

    /**
     * Reset all states
     */
    private fun resetStates() {
        Log.d(TAG, "🔄 Resetting states")
        notifyBugsnag(
            "States Reset", mapOf(
                "old_anydeskAccepted" to anydeskAccepted.toString(),
                "old_currentStep" to currentStep.toString(),
                "action" to "STATES_RESET"
            )
        )
        anydeskAccepted = false
        currentStep = 0
    }

    /**
     * Safe recycle node
     */
    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        if (node == null) return
        try {
            node.recycle()
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Show toast notification
     */
    private fun showToastNotification(message: String) {
        Log.i(TAG, "📢 $message")
        notifyBugsnag("Toast Notification", mapOf("message" to message))
    }

    override fun onInterrupt() {
        Log.d(TAG, "🔴 Service Interrupted")
        isServiceEnabled = false
        notifyBugsnag("Service Interrupted", mapOf("action" to "SERVICE_INTERRUPTED"))
        stopPolling()
        handler.removeCallbacksAndMessages(null)
        isProcessing = false
        resetStates()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "🔴 Service Unbound")
        isServiceEnabled = false
        notifyBugsnag("Service Unbound", mapOf("action" to "SERVICE_UNBOUND"))
        stopPolling()
        handler.removeCallbacksAndMessages(null)
        isProcessing = false
        resetStates()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🔴 Service Destroyed")
        isServiceEnabled = false
        notifyBugsnag("Service Destroyed", mapOf("action" to "SERVICE_DESTROYED"))
        stopPolling()
        handler.removeCallbacksAndMessages(null)
        isProcessing = false
        resetStates()
    }
}