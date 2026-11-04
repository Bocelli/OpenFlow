package com.openflow.android.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
        Log.d(TAG, "FlowAccessibilityService onServiceConnected!")

        // Configurazione dinamica per intercettare tutti gli eventi finestra e input
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 50
        }

        initBubbleOverlay()

        // Mostra la bolla se l'utente ha scelto di averla sempre attiva o al primo avvio
        if (!prefs.showOnlyWhenFocused) {
            bubbleOverlay?.show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_BUBBLE -> {
                ensureOverlayInitialized()
                bubbleOverlay?.show()
            }
            ACTION_HIDE_BUBBLE -> {
                bubbleOverlay?.hide()
            }
        }
        return START_STICKY
    }

    fun triggerShowBubble() {
        ensureOverlayInitialized()
        bubbleOverlay?.show()
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

        val source = event.source
        val root = rootInActiveWindow
        val focusedNode = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        val isEditableField = (source?.isEditable == true) || (focusedNode?.isEditable == true)

        if (isEditableField) {
            val target = if (source?.isEditable == true) source else focusedNode
            if (target != null) {
                try {
                    activeTargetNode?.recycle()
                    activeTargetNode = AccessibilityNodeInfo.obtain(target)
                    currentContextText = target.text?.toString()
                } catch (_: Exception) {}
            }

            if (prefs.showOnlyWhenFocused) {
                ensureOverlayInitialized()
                bubbleOverlay?.show()
            }
        }
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

            response.onSuccess { polishedText ->
                if (polishedText.isNotBlank()) {
                    insertTextIntoActiveField(polishedText)
                    bubbleOverlay?.updateState(BubbleState.SUCCESS)
                } else {
                    bubbleOverlay?.updateState(BubbleState.IDLE)
                }
            }.onFailure { error ->
                Log.e(TAG, "Errore AI Pipeline: ${error.message}", error)
                bubbleOverlay?.updateState(BubbleState.ERROR)
            }
        }
    }

    private fun insertTextIntoActiveField(newText: String) {
        var target = activeTargetNode
        if (target == null || !target.isEditable) {
            val root = rootInActiveWindow
            target = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }

        if (target == null || !target.isEditable) {
            copyToClipboard(newText)
            return
        }

        val existingText = target.text?.toString() ?: ""
        val finalText = if (existingText.isNotBlank() && !existingText.endsWith(" ")) {
            "$existingText $newText"
        } else {
            "$existingText$newText"
        }

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
        }

        val success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

        if (!success) {
            // shortcut: fallback tramite Clipboard + ACTION_PASTE per campi custom o WebView
            copyToClipboard(newText)
            target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
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
        bubbleOverlay?.hide()
        audioRecorder.cancelRecording()
        try { activeTargetNode?.recycle() } catch (_: Exception) {}
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
