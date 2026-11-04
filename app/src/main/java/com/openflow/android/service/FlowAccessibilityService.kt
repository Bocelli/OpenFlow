package com.openflow.android.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import java.io.File

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
        prefs = PreferencesManager(this)
        audioRecorder = AudioRecorderManager(this)
        aiClient = AiPipelineClient(prefs)

        initBubbleOverlay()
    }

    private fun initBubbleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Permesso SYSTEM_ALERT_WINDOW non ancora concesso.")
            return
        }

        bubbleOverlay = FloatingBubbleOverlay(
            context = this,
            prefs = prefs,
            onStartRecording = { handleStartRecording() },
            onStopRecording = { handleStopRecording() },
            onCancelRecording = { audioRecorder.cancelRecording() },
            getAmplitude = { audioRecorder.getMaxAmplitude() }
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source
                if (node != null && node.isEditable) {
                    activeTargetNode?.recycle()
                    activeTargetNode = AccessibilityNodeInfo.obtain(node)
                    currentContextText = node.text?.toString()

                    if (prefs.showOnlyWhenFocused) {
                        ensureOverlayInitialized()
                        bubbleOverlay?.show()
                    }
                } else {
                    if (prefs.showOnlyWhenFocused && bubbleOverlay?.currentState == BubbleState.IDLE) {
                        mainHandler.postDelayed({
                            // Chiudi la bolla solo se non siamo ancora su un campo editabile
                            if (activeTargetNode == null || activeTargetNode?.isFocused == false) {
                                bubbleOverlay?.hide()
                            }
                        }, 300)
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Se la finestra cambia totalmente e siamo in IDLE, pulisci il nodo target
                if (bubbleOverlay?.currentState == BubbleState.IDLE) {
                    activeTargetNode?.recycle()
                    activeTargetNode = null
                }
            }
        }
    }

    private fun ensureOverlayInitialized() {
        if (bubbleOverlay == null && Settings.canDrawOverlays(this)) {
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
            recordedFile.delete() // pulizia file audio temporaneo

            response.onSuccess { polishedText ->
                if (polishedText.isNotBlank()) {
                    insertTextIntoActiveField(polishedText)
                    bubbleOverlay?.updateState(BubbleState.SUCCESS)
                } else {
                    bubbleOverlay?.updateState(BubbleState.IDLE)
                }
            }.onFailure { error ->
                Log.e(TAG, "Errore nella pipeline AI: ${error.message}", error)
                bubbleOverlay?.updateState(BubbleState.ERROR)
            }
        }
    }

    private fun insertTextIntoActiveField(newText: String) {
        // Tenta prima il nodo precedentemente memorizzato
        var target = activeTargetNode
        if (target == null || !target.isEditable) {
            // Se perso, cerca il nodo attualmente focalizzato nella finestra attiva
            val root = rootInActiveWindow
            target = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }

        if (target == null || !target.isEditable) {
            // Fallback: copia negli appunti se nessun target è raggiungibile
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
        bubbleOverlay?.hide()
        audioRecorder.cancelRecording()
        activeTargetNode?.recycle()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FlowAccessibilityService"
    }
}
