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
import android.view.accessibility.AccessibilityWindowInfo
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

    private val hideBubbleRunnable = Runnable {
        if (!prefs.showOnlyWhenFocused || bubbleOverlay?.currentState != BubbleState.IDLE) return@Runnable

        // Controllo di sicurezza: se la tastiera è ancora aperta a schermo, non nascondere
        if (isKeyboardVisible()) {
            Log.d(TAG, "hideBubble annullato: tastiera ancora visibile a schermo")
            bubbleOverlay?.wakeUpFromStealth()
            bubbleOverlay?.cancelStealth()
            return@Runnable
        }

        // Se c'è ancora un campo di testo focalizzato, non nascondere
        val activeInput = findActiveFocusedInput()
        if (activeInput != null) {
            Log.d(TAG, "hideBubble annullato: campo ancora focalizzato")
            updateActiveTarget(activeInput)
            activeInput.recycle()
            bubbleOverlay?.wakeUpFromStealth()
            bubbleOverlay?.cancelStealth()
            return@Runnable
        }

        Log.d(TAG, "hideBubble confermato: nessun input o tastiera attiva")
        bubbleOverlay?.hide()
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

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.notificationTimeout = 40
        serviceInfo = info

        initBubbleOverlay()
        bubbleOverlay?.show(force = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_BUBBLE -> triggerShowBubble()
            ACTION_HIDE_BUBBLE -> triggerHideBubble()
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

    fun isBubbleVisible(): Boolean {
        return bubbleOverlay?.isVisible == true
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
        if (event == null || event.packageName == packageName) return

        val pkg = event.packageName?.toString()?.lowercase() ?: ""
        val cls = event.className?.toString() ?: ""
        val source = event.source

        try {
            ensureOverlayInitialized()

            val keyboardOpen = isKeyboardVisible()
            val eventIsInput = isNodeInputCapable(source, cls, pkg)

            if (eventIsInput && source != null) {
                updateActiveTarget(source)
            }

            var hasActiveInput = eventIsInput || keyboardOpen
            if (!hasActiveInput) {
                val activeInput = findActiveFocusedInput()
                if (activeInput != null) {
                    hasActiveInput = true
                    updateActiveTarget(activeInput)
                    activeInput.recycle()
                }
            }

            if (hasActiveInput) {
                // Risveglio immediato dallo Stealth Mode (28% -> 100% di opacità):
                // L'utente ha la tastiera aperta o sta digitando su Facebook, YouTube, WhatsApp, BlueMail, ecc.
                Log.d(TAG, "Input/Tastiera attiva rilevata ($pkg, kb=$keyboardOpen, input=$eventIsInput): risveglio Stealth Mode al 100%")
                mainHandler.removeCallbacks(hideBubbleRunnable)
                bubbleOverlay?.wakeUpFromStealth()
                bubbleOverlay?.cancelStealth()
            } else {
                // Nessun input attivo e nessuna tastiera aperta:
                if (prefs.showOnlyWhenFocused) {
                    scheduleHideBubbleIfIdle(delayMs = 700)
                } else {
                    // In modalità persistente, torna in semi-trasparenza dopo 3.5s per non coprire i contenuti
                    bubbleOverlay?.scheduleStealth(3500)
                }
            }
        } finally {
            try { source?.recycle() } catch (_: Exception) {}
        }
    }

    private fun scheduleHideBubbleIfIdle(delayMs: Long = 700) {
        if (!prefs.showOnlyWhenFocused) return
        mainHandler.removeCallbacks(hideBubbleRunnable)
        mainHandler.postDelayed(hideBubbleRunnable, delayMs)
    }

    /**
     * Rileva se la finestra della tastiera software (IME) è presente e aperta a schermo.
     * Metodo a prova di bomba valido su qualsiasi versione Android e OEM (Xiaomi, Samsung, Pixel).
     */
    private fun isKeyboardVisible(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val tempRect = android.graphics.Rect()
                windows.any { window ->
                    if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        window.getBoundsInScreen(tempRect)
                        tempRect.height() > 100
                    } else {
                        false
                    }
                }
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Rileva se un nodo o una classe rappresenta un campo di testo o un input interattivo.
     * Compatibile con Facebook (Litho/React), BlueMail (WebViews), YouTube, WhatsApp, Telegram, ecc.
     */
    private fun isNodeInputCapable(source: AccessibilityNodeInfo?, cls: String, pkg: String): Boolean {
        if (pkg.contains("inputmethod") || pkg.contains("keyboard") || pkg.contains(".ime")) {
            return true
        }
        if (source == null) return false

        if (source.isEditable) return true

        if (cls.contains("EditText", ignoreCase = true) ||
            cls.contains("TextField", ignoreCase = true) ||
            cls.contains("AutoComplete", ignoreCase = true) ||
            cls.contains("Search", ignoreCase = true) ||
            cls.contains("Input", ignoreCase = true) ||
            cls.contains("Compose", ignoreCase = true) ||
            cls.contains("Editor", ignoreCase = true) ||
            cls.contains("WebView", ignoreCase = true)
        ) {
            return true
        }

        try {
            val actions = source.actionList
            if (actions.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT || it.id == AccessibilityNodeInfo.ACTION_PASTE }) {
                return true
            }
        } catch (_: Exception) {}

        return false
    }

    /**
     * Cerca un campo con focus di input attivo su tutte le finestre visibili a schermo.
     */
    private fun findActiveFocusedInput(): AccessibilityNodeInfo? {
        try {
            val globalFocus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (globalFocus != null) return globalFocus
        } catch (_: Exception) {}

        try {
            val root = rootInActiveWindow
            if (root != null) {
                val input = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                root.recycle()
                if (input != null) return input
            }
        } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                for (window in windows) {
                    val root = window.root ?: continue
                    val input = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    root.recycle()
                    if (input != null) return input
                }
            } catch (_: Exception) {}
        }

        return null
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
                    try {
                        com.openflow.android.data.TranscriptionHistoryManager(this@FlowAccessibilityService).add(
                            text = textToInsert,
                            provider = prefs.activeProvider.name,
                            preset = prefs.activePreset.name
                        )
                    } catch (_: Exception) {}
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
        // Salvagente universale: copia sempre il testo formattato negli appunti
        copyToClipboard(newText)

        var target: AccessibilityNodeInfo? = activeTargetNode
        var needsRecycleTarget = false

        // 1. Cerca il nodo con focus di input attivo nella finestra corrente
        if (target == null || !target.refresh()) {
            try {
                val root = rootInActiveWindow
                if (root != null) {
                    target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                    needsRecycleTarget = true
                    root.recycle()
                }
            } catch (_: Exception) {}
        }

        // 2. Cerca in tutte le finestre interattive (per popup, dialog, bottom-sheet di Facebook, YouTube, BlueMail)
        if (target == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                for (window in windows) {
                    val root = window.root ?: continue
                    val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                    root.recycle()
                    if (focused != null) {
                        target = focused
                        needsRecycleTarget = true
                        break
                    }
                }
            } catch (_: Exception) {}
        }

        if (target == null) {
            Log.d(TAG, "Nessun nodo attivo trovato: testo salvato negli appunti")
            return
        }

        try {
            var inserted = false

            // Tentativo 1: ACTION_SET_TEXT con eventuale testo pre-esistente
            val existingText = getSanitizedFieldText(target)
            val finalText = if (existingText.isNotBlank()) {
                if (existingText.endsWith(" ")) "$existingText$newText" else "$existingText $newText"
            } else {
                newText
            }

            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
            }
            inserted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

            // Tentativo 2: Fallback diretto ACTION_PASTE (indispensabile per WebViews di BlueMail, React Native di Facebook, YouTube)
            if (!inserted) {
                inserted = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }

            Log.d(TAG, "Risultato inserimento testo: inserted=$inserted in ${target.className}")
        } catch (e: Exception) {
            Log.e(TAG, "Eccezione inserimento testo: ${e.message}", e)
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
