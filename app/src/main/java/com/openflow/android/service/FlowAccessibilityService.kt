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

        // Modifica l'oggetto serviceInfo esistente per NON perdere i permessi definiti nell'XML (es. canRetrieveWindowContent="true")
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.notificationTimeout = 50
        serviceInfo = info

        initBubbleOverlay()

        // Mostra subito la bolla se l'utente non ha impostato la modalità a comparsa esclusiva
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

        var sourceNode: AccessibilityNodeInfo? = null
        var focusedNode: AccessibilityNodeInfo? = null

        try {
            sourceNode = event.source
            val root = rootInActiveWindow
            focusedNode = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

            val isEditableField = (sourceNode?.isEditable == true) || (focusedNode?.isEditable == true)

            if (isEditableField) {
                val target = if (sourceNode?.isEditable == true) sourceNode else focusedNode
                if (target != null) {
                    try {
                        activeTargetNode?.recycle()
                        activeTargetNode = AccessibilityNodeInfo.obtain(target)
                        currentContextText = getSanitizedFieldText(target).ifBlank { null }
                    } catch (_: Exception) {}
                }

                ensureOverlayInitialized()
                bubbleOverlay?.show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore onAccessibilityEvent: ${e.message}", e)
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
        if (target == null || !target.isEditable) {
            val root = rootInActiveWindow
            target = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }

        if (target == null || !target.isEditable) {
            copyToClipboard(newText)
            return
        }

        // Filtra via l'eventuale hint placeholder prima di concatenare
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
            // shortcut: fallback tramite Clipboard + ACTION_PASTE per campi custom o WebView
            copyToClipboard(newText)
            target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
    }

    /**
     * Risolve il bug critico di WhatsApp/Telegram:
     * Quando un campo è vuoto, AccessibilityNodeInfo.text spesso restituisce l'hint ("Message", "Messaggio").
     * Questa funzione garantisce che l'hint venga riconosciuto come campo vuoto.
     */
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

        // Riconoscimento placeholder noti
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
