package com.openflow.android.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.openflow.android.ai.AiPipelineClient
import com.openflow.android.audio.AudioRecorderManager
import com.openflow.android.data.PreferencesManager
import com.openflow.android.ui.BubbleState
import com.openflow.android.ui.FloatingBubbleOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class FlowAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var prefs: PreferencesManager
    private lateinit var audioRecorder: AudioRecorderManager
    private lateinit var aiClient: AiPipelineClient
    private var bubbleOverlay: FloatingBubbleOverlay? = null

    private var activeTargetNode: AccessibilityNodeInfo? = null
    private var currentContextText: String? = null

    private val checkWindowFocusRunnable = Runnable {
        try {
            val root = rootInActiveWindow ?: return@Runnable
            try {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                try {
                    if (focused != null && isLikelyInputField(focused)) {
                        mainHandler.removeCallbacks(hideBubbleRunnable)
                        updateActiveTarget(focused)
                        ensureOverlayInitialized()
                        if (prefs.showOnlyWhenFocused) {
                            bubbleOverlay?.show()
                        }
                    } else {
                        scheduleHideBubbleIfIdle()
                    }
                } finally {
                    try { focused?.recycle() } catch (_: Exception) {}
                }
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore checkWindowFocus: ${e.message}", e)
        }
    }

    private val hideBubbleRunnable = Runnable {
        if (prefs.showOnlyWhenFocused && bubbleOverlay?.currentState == BubbleState.IDLE) {
            bubbleOverlay?.hide()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = PreferencesManager(this)
        audioRecorder = AudioRecorderManager(this)
        aiClient = AiPipelineClient(prefs)
        Log.d(TAG, "FlowAccessibilityService onCreate")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "FlowAccessibilityService onServiceConnected!")

        // Configura eventi mirati (MAI TYPES_ALL_MASK che satura l'IPC di sistema e blocca il touch)
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.notificationTimeout = 120
        serviceInfo = info

        initBubbleOverlay()

        // Mostra subito solo se l'utente ha scelto la modalità sempre visibile
        if (!prefs.showOnlyWhenFocused) {
            bubbleOverlay?.show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_BUBBLE -> {
                triggerShowBubble()
            }
            ACTION_HIDE_BUBBLE -> {
                bubbleOverlay?.hide()
            }
        }
        return START_STICKY
    }

    fun triggerShowBubble() {
        mainHandler.removeCallbacks(hideBubbleRunnable)
        ensureOverlayInitialized()
        bubbleOverlay?.show(force = true)
    }

    fun triggerHideBubble() {
        mainHandler.removeCallbacks(hideBubbleRunnable)
        if (bubbleOverlay?.currentState == BubbleState.IDLE) {
            bubbleOverlay?.hide()
        }
    }

    private fun initBubbleOverlay() {
        if (bubbleOverlay == null) {
            bubbleOverlay = FloatingBubbleOverlay(
                context = this,
                prefs = prefs,
                onStartRecording = { handleStartRecording() },
                onStopRecording = { handleStopRecording() },
                onCancelRecording = { audioRecorder.cancelRecording() },
                getAmplitude = { audioRecorder.getMaxAmplitude() }
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Ignora gli eventi generati dalla nostra stessa app o overlay
        if (event.packageName == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val source = event.source
                if (source != null) {
                    try {
                        if (isLikelyInputField(source)) {
                            mainHandler.removeCallbacks(hideBubbleRunnable)
                            updateActiveTarget(source)
                            ensureOverlayInitialized()
                            if (prefs.showOnlyWhenFocused) {
                                bubbleOverlay?.show()
                            }
                        } else {
                            scheduleHideBubbleIfIdle()
                        }
                    } finally {
                        try { source.recycle() } catch (_: Exception) {}
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                mainHandler.removeCallbacks(checkWindowFocusRunnable)
                mainHandler.postDelayed(checkWindowFocusRunnable, 150)
            }
        }
    }

    private fun scheduleHideBubbleIfIdle() {
        if (prefs.showOnlyWhenFocused && bubbleOverlay?.currentState == BubbleState.IDLE) {
            mainHandler.removeCallbacks(hideBubbleRunnable)
            mainHandler.postDelayed(hideBubbleRunnable, 350)
        }
    }

    private fun isLikelyInputField(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isEditable) return true
        val className = node.className?.toString() ?: return false
        return className.contains("EditText", ignoreCase = true) ||
                className.contains("AutoCompleteTextView", ignoreCase = true) ||
                className.contains("SearchAutoComplete", ignoreCase = true) ||
                className.contains("ExtractEditText", ignoreCase = true) ||
                className.contains("TextField", ignoreCase = true) ||
                className.contains("TextInput", ignoreCase = true)
    }

    private fun updateActiveTarget(node: AccessibilityNodeInfo) {
        try {
            activeTargetNode?.recycle()
            activeTargetNode = AccessibilityNodeInfo.obtain(node)
            currentContextText = getSanitizedFieldText(node).ifBlank { null }
        } catch (_: Exception) {}
    }

    private fun ensureOverlayInitialized() {
        if (bubbleOverlay == null) {
            initBubbleOverlay()
        }
    }

    private fun handleStartRecording() {
        val result = audioRecorder.startRecording()
        if (result.isFailure) {
            bubbleOverlay?.updateState(BubbleState.ERROR)
        }
    }

    private fun handleStopRecording() {
        val recordedFile = audioRecorder.stopRecording()
        if (recordedFile == null || !recordedFile.exists()) {
            bubbleOverlay?.updateState(BubbleState.ERROR)
            return
        }

        serviceScope.launch {
            val response = aiClient.processSpeechToPolishedText(recordedFile, currentContextText)
            recordedFile.delete()

            response.onSuccess { output ->
                val textToInsert = if (output.isFallback && output.errorMessage != null) {
                    showNotificationAlert("⚠️ OpenFlow: Fallito LLM", output.errorMessage)
                    "[⚠️ Fallback Whisper - Errore LLM: ${output.errorMessage}]\n\n${output.text}"
                } else {
                    output.text
                }

                if (textToInsert.isNotBlank()) {
                    insertTextIntoActiveField(textToInsert)
                    bubbleOverlay?.updateState(BubbleState.SUCCESS)
                } else {
                    bubbleOverlay?.updateState(BubbleState.IDLE)
                }
            }.onFailure { error ->
                Log.e(TAG, "Errore AI Pipeline: ${error.message}", error)
                showNotificationAlert("❌ OpenFlow: Errore Registrazione/AI", error.message ?: "Errore sconosciuto")
                bubbleOverlay?.updateState(BubbleState.ERROR)
            }
        }
    }

    private fun insertTextIntoActiveField(newText: String) {
        var target = activeTargetNode
        var needsRecycleTarget = false

        if (target == null || !target.isEditable) {
            val root = rootInActiveWindow
            target = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            needsRecycleTarget = true
            try { root?.recycle() } catch (_: Exception) {}
        }

        if (target == null || !target.isEditable) {
            copyToClipboard(newText)
            if (needsRecycleTarget) {
                try { target?.recycle() } catch (_: Exception) {}
            }
            return
        }

        try {
            val existingText = getSanitizedFieldText(target)
            val finalText = if (existingText.isNotBlank()) {
                if (existingText.endsWith(" ")) "$existingText$newText" else "$existingText $newText"
            } else {
                newText
            }

            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
            }

            val success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

            if (!success) {
                copyToClipboard(newText)
                target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
        } finally {
            if (needsRecycleTarget) {
                try { target.recycle() } catch (_: Exception) {}
            }
        }
    }

    private fun getSanitizedFieldText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText) {
            return ""
        }
        val text = node.text?.toString()?.trim() ?: return ""
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString()?.trim() else null
        if (!hint.isNullOrEmpty() && text.equals(hint, ignoreCase = true)) {
            return ""
        }

        val lower = text.lowercase()
        val placeholderKeywords = listOf(
            "message", "messaggio", "scrivi un messaggio", "scrivi un messaggio...",
            "type a message", "type a message...", "cerca o digita...", "text message",
            "invia un messaggio", "send message"
        )
        if (placeholderKeywords.any { lower == it }) {
            return ""
        }

        return text
    }

    private fun showNotificationAlert(title: String, message: String) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "openflow_status_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(channelId, "Stato OpenFlow", android.app.NotificationManager.IMPORTANCE_HIGH)
                notificationManager.createNotificationChannel(channel)
            }
            val notif = androidx.core.app.NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(2002, notif)
        } catch (_: Exception) {}
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("OpenFlow Text", text)
        clipboard.setPrimaryClip(clip)
    }

    override fun onInterrupt() {
        bubbleOverlay?.hide()
        audioRecorder.cancelRecording()
    }

    override fun onDestroy() {
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        bubbleOverlay?.destroy()
        audioRecorder.cancelRecording()
        try { activeTargetNode?.recycle() } catch (_: Exception) {}
        activeTargetNode = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FlowAccessibilityService"
        const val ACTION_SHOW_BUBBLE = "com.openflow.android.SHOW_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "com.openflow.android.HIDE_BUBBLE"

        var instance: FlowAccessibilityService? = null
            private set
    }
}
