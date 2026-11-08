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
import com.openflow.android.ai.PipelineOutput
import com.openflow.android.audio.AudioRecorderManager
import com.openflow.android.data.PreferencesManager
import com.openflow.android.ui.BubbleState
import com.openflow.android.ui.FloatingBubbleOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FlowAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var prefs: PreferencesManager
    private lateinit var audioRecorder: AudioRecorderManager
    private lateinit var aiClient: AiPipelineClient
    private var bubbleOverlay: FloatingBubbleOverlay? = null
    private var currentProcessingJob: Job? = null

    private var activeTargetNode: AccessibilityNodeInfo? = null

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
            val activeCls = activeInput.className?.toString() ?: ""
            val activePkg = activeInput.packageName?.toString()?.lowercase() ?: ""
            if (isNodeInputCapable(activeInput, activeCls, activePkg)) {
                Log.d(TAG, "hideBubble annullato: campo ancora focalizzato ($activePkg, $activeCls)")
                updateActiveTarget(activeInput)
                activeInput.recycle()
                bubbleOverlay?.wakeUpFromStealth()
                bubbleOverlay?.cancelStealth()
                return@Runnable
            }
            activeInput.recycle()
        }

        Log.d(TAG, "hideBubble confermato: nessun input o tastiera attiva")
        bubbleOverlay?.hide()
    }

    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                android.content.Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen OFF ricevuto: disattivazione overlay per risparmio energetico e prevenzione timeout display")
                    mainHandler.removeCallbacks(hideBubbleRunnable)
                    bubbleOverlay?.onScreenOff()
                }
                android.content.Intent.ACTION_SCREEN_ON, android.content.Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "Screen ON / USER_PRESENT ricevuto: riattivazione overlay")
                    ensureOverlayInitialized()
                    bubbleOverlay?.onScreenOn()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = PreferencesManager(this)
        audioRecorder = AudioRecorderManager(this)
        aiClient = AiPipelineClient(prefs)
        showOngoingNotification()

        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, filter)
        Log.d(TAG, "FlowAccessibilityService onCreate con screenStateReceiver attivo")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "FlowAccessibilityService onServiceConnected!")
        showOngoingNotification()

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.notificationTimeout = 100
        serviceInfo = info

        initBubbleOverlay()
        if (prefs.showOnlyWhenFocused) {
            scheduleHideBubbleIfIdle(delayMs = 700)
        } else {
            bubbleOverlay?.show(force = true)
        }
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

    fun updateNotchPosition() {
        bubbleOverlay?.updateNotchPosition()
    }

    private fun initBubbleOverlay() {
        if (bubbleOverlay == null) {
            bubbleOverlay = FloatingBubbleOverlay(
                context = this,
                prefs = prefs,
                onStartRecording = { handleStartRecording() },
                onStopRecording = { handleStopRecording() },
                onCancelRecording = {
                    stopAudioRecordService()
                    audioRecorder.cancelRecording()
                    currentProcessingJob?.cancel()
                    currentProcessingJob = null
                },
                getAmplitude = { audioRecorder.getMaxAmplitude() }
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName == packageName) return

        val pkg = event.packageName?.toString()?.lowercase() ?: ""
        val cls = event.className?.toString() ?: ""
        val source = event.source

        // Protezione Tapjacking e Privacy: nasconde l'overlay su app bancarie, password manager e schermate permessi
        if (isSensitivePackage(pkg)) {
            mainHandler.removeCallbacks(hideBubbleRunnable)
            bubbleOverlay?.hide()
            try { source?.recycle() } catch (_: Exception) {}
            return
        }

        try {
            ensureOverlayInitialized()

            val isWindowChangeEvent = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            val keyboardOpen = isKeyboardVisible(forceRefresh = isWindowChangeEvent)
            val eventIsInput = isNodeInputCapable(source, cls, pkg)

            if (eventIsInput && source != null) {
                updateActiveTarget(source)
            }

            var hasActiveInput = eventIsInput || keyboardOpen
            // Se l'evento proviene dal launcher desktop o dalla schermata di blocco/tendina di sistema,
            // non cerchiamo focus residui di app lasciate aperte in background.
            val isSystemOrLauncher = pkg.contains("launcher") || pkg.contains("globallauncher") || pkg.contains("systemui")
            if (!hasActiveInput && !isSystemOrLauncher) {
                val activeInput = findActiveFocusedInput()
                if (activeInput != null) {
                    val activeCls = activeInput.className?.toString() ?: ""
                    val activePkg = activeInput.packageName?.toString()?.lowercase() ?: ""
                    if (isNodeInputCapable(activeInput, activeCls, activePkg)) {
                        hasActiveInput = true
                        updateActiveTarget(activeInput)
                    }
                    activeInput.recycle()
                }
            }

            if (hasActiveInput) {
                // Risveglio immediato dallo Stealth Mode (28% -> 100% di opacità):
                // L'utente ha la tastiera aperta o sta digitando su Facebook, YouTube, WhatsApp, BlueMail, ecc.
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

    private var cachedKeyboardVisible: Boolean = false
    private var lastKeyboardCheckTimeMs: Long = 0L

    /**
     * Rileva se la finestra della tastiera software (IME) è presente e aperta a schermo.
     * Con throttling a 300ms e ciclo ad interruzione rapida per azzerare il carico IPC su Binder e HyperOS.
     */
    private fun isKeyboardVisible(forceRefresh: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!forceRefresh && (now - lastKeyboardCheckTimeMs < 300L)) {
            return cachedKeyboardVisible
        }
        lastKeyboardCheckTimeMs = now
        return try {
            val tempRect = android.graphics.Rect()
            val windowList = windows
            var found = false
            for (window in windowList) {
                window.getBoundsInScreen(tempRect)
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && tempRect.height() > 100) {
                    found = true
                    break
                }
            }
            cachedKeyboardVisible = found
            found
        } catch (e: Exception) {
            Log.e(TAG, "isKeyboardVisible error: ${e.message}")
            cachedKeyboardVisible
        }
    }

    /**
     * Rileva se un nodo o una classe rappresenta un campo di testo o un input interattivo.
     * Compatibile con Facebook (Litho/React), BlueMail (WebViews), YouTube, WhatsApp, Telegram, ecc.
     */
    private fun isSensitivePackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return pkg.contains("permissioncontroller") ||
                pkg.contains("packageinstaller") ||
                pkg.contains("authenticator") ||
                pkg.contains("bank") ||
                pkg.contains("poste") ||
                pkg.contains("wallet") ||
                pkg.contains("keychain") ||
                pkg.contains("keepass") ||
                pkg.contains("bitwarden") ||
                pkg.contains("1password")
    }

    private fun isNodeInputCapable(source: AccessibilityNodeInfo?, cls: String, pkg: String): Boolean {
        if (isSensitivePackage(pkg)) return false
        if (source?.isPassword == true) return false
        if (cls.contains("Password", ignoreCase = true) || cls.contains("PinEntry", ignoreCase = true)) return false

        if (pkg.contains("inputmethod") || pkg.contains("keyboard") || pkg.contains(".ime")) {
            return true
        }
        if (source == null) return false

        // Evita categoricamente falsi positivi da desktop/widget del launcher e tendina di sistema
        if (pkg.contains("launcher") || pkg.contains("globallauncher") || pkg.contains("systemui")) {
            if (!source.isEditable) return false
        }

        if (source.isEditable) return true

        try {
            val actions = source.actionList
            if (actions.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT || it.id == AccessibilityNodeInfo.ACTION_PASTE }) {
                return true
            }
        } catch (_: Exception) {}

        if (cls.contains("EditText", ignoreCase = true) ||
            cls.contains("TextField", ignoreCase = true) ||
            cls.contains("AutoComplete", ignoreCase = true) ||
            cls.contains("ExtractEditText", ignoreCase = true)
        ) {
            return true
        }

        return false
    }

    /**
     * Cerca un campo con focus di input attivo SOLO nella finestra in primo piano (attiva).
     * Evita rigorosamente di agganciare campi di testo di app rimaste in background quando l'utente è nella Home.
     */
    private fun findActiveFocusedInput(): AccessibilityNodeInfo? {
        try {
            val root = rootInActiveWindow ?: return null
            val rootPkg = root.packageName?.toString()?.lowercase() ?: ""

            // Se l'utente è su un'app sensibile, sulla schermata Home o tendina di sistema, nessun campo è attivo
            if (isSensitivePackage(rootPkg) || rootPkg.contains("launcher") || rootPkg.contains("globallauncher") || rootPkg.contains("systemui")) {
                root.recycle()
                return null
            }

            val input = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            root.recycle()
            if (input != null) {
                if (input.isPassword) {
                    input.recycle()
                    return null
                }
                return input
            }
        } catch (_: Exception) {}

        return null
    }

    private fun updateActiveTarget(node: AccessibilityNodeInfo) {
        // Stesso nodo: riciclarlo prima di obtain() lo copierebbe già riciclato (Android < 13)
        if (node === activeTargetNode) return
        try {
            activeTargetNode?.recycle()
            activeTargetNode = AccessibilityNodeInfo.obtain(node)
        } catch (_: Exception) {}
    }

    private fun ensureOverlayInitialized() {
        if (bubbleOverlay == null) {
            initBubbleOverlay()
        }
    }

    private fun handleStartRecording() {
        try {
            startService(Intent(this, AudioRecordService::class.java))
        } catch (_: Exception) {}

        val result = audioRecorder.startRecording()
        if (result.isFailure) {
            stopAudioRecordService()
            bubbleOverlay?.updateState(BubbleState.ERROR)
        }
    }

    private fun handleStopRecording() {
        stopAudioRecordService()
        val hasVoice = audioRecorder.hasDetectedVoiceActivity()
        val recordedFile = audioRecorder.stopRecording()
        if (recordedFile == null || !recordedFile.exists()) {
            bubbleOverlay?.updateState(BubbleState.ERROR)
            return
        }

        // SCUDO 1: Se l'audio e silenzio assoluto o rumore di fondo senza voce (< soglia VAD),
        // chiudiamo immediatamente senza chiamare le API, evitando allucinazioni e consumo token.
        if (!hasVoice) {
            Log.i(TAG, "Nessuna attivita vocale rilevata (silenzio/rumore sotto soglia). Ripristino IDLE.")
            try { recordedFile.delete() } catch (_: Exception) {}
            bubbleOverlay?.updateState(BubbleState.IDLE)
            return
        }

        currentProcessingJob?.cancel()
        currentProcessingJob = serviceScope.launch {
            // Qualsiasi eccezione imprevista (es. header non valido) senza questo catch crasha l'app
            // e lascia la bolla bloccata su PROCESSING.
            val response: Result<PipelineOutput> = try {
                aiClient.processSpeechToPolishedText(recordedFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                recordedFile.delete()
            }

            response.onSuccess { output ->
                if (output.text.isBlank()) {
                    if (output.errorMessage != null) {
                        showNotificationAlert("⚠️ OpenFlow: Trascrizione non riuscita", output.errorMessage)
                    }
                    bubbleOverlay?.updateState(if (output.errorMessage != null) BubbleState.ERROR else BubbleState.IDLE)
                    return@onSuccess
                }

                // Inserimento pulito e silenzioso: anche in caso di fallback Whisper, nessun prefisso di errore
                val wasPassword = insertTextIntoActiveField(output.text)
                bubbleOverlay?.updateState(BubbleState.SUCCESS)

                // Salva nella cronologia locale SOLO se NON era un campo password/sensibile
                if (!wasPassword) {
                    try {
                        com.openflow.android.data.TranscriptionHistoryManager(this@FlowAccessibilityService).add(
                            text = output.text,
                            provider = prefs.activeProvider.name,
                            preset = prefs.activePreset.name
                        )
                    } catch (_: Exception) {}
                }
            }.onFailure { error ->
                Log.e(TAG, "Errore AI Pipeline: ${error.message}", error)
                showNotificationAlert("❌ OpenFlow: Errore Registrazione/AI", error.message ?: "Errore sconosciuto")
                bubbleOverlay?.updateState(BubbleState.ERROR)
            }
        }
    }

    private suspend fun insertTextIntoActiveField(newText: String): Boolean {
        val retryDelays = listOf(0L, 85L, 170L)
        var successfullyInserted = false
        var isPasswordField = false

        for ((attemptIndex, delayMs) in retryDelays.withIndex()) {
            if (delayMs > 0) {
                kotlinx.coroutines.delay(delayMs)
            }

            var target: AccessibilityNodeInfo? = null
            var needsRecycleTarget = false

            try {
                // 1. Cerca il nodo con focus di input attivo nella finestra in primo piano (attiva)
                val root = rootInActiveWindow
                if (root != null) {
                    val rootPkg = root.packageName?.toString()?.lowercase() ?: ""
                    if (!isSensitivePackage(rootPkg) && !rootPkg.contains("launcher") && !rootPkg.contains("globallauncher") && !rootPkg.contains("systemui")) {
                        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                        if (focused != null) {
                            target = focused
                            needsRecycleTarget = true
                        }
                    }
                    root.recycle()
                }

                // 2. Se non ancora agganciato, usa il target salvato dall'ultimo tocco dell'utente
                if (target == null && activeTargetNode != null) {
                    if (activeTargetNode?.refresh() == true) {
                        target = activeTargetNode
                        needsRecycleTarget = false
                    }
                }

                // 3. Fallback per dialog, popup e bottom-sheet multischeda
                if (target == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    for (window in windows) {
                        val winRoot = window.root ?: continue
                        val focused = winRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            ?: winRoot.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                        winRoot.recycle()
                        if (focused != null) {
                            target = focused
                            needsRecycleTarget = true
                            break
                        }
                    }
                }

                if (target == null) {
                    Log.d(TAG, "Tentativo $attemptIndex: nessun nodo con focus trovato")
                    continue
                }

                if (target.isPassword || target.className?.contains("Password", ignoreCase = true) == true) {
                    isPasswordField = true
                }

                // RIPRISTINO DEL FOCUS: essenziale per far accettare ACTION_SET_TEXT e ACTION_PASTE
                if (!target.isFocused) {
                    target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }

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
                var inserted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

                // Tentativo 2: Fallback diretto ACTION_PASTE
                if (!inserted) {
                    inserted = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }

                Log.d(TAG, "Tentativo $attemptIndex inserimento testo: inserted=$inserted in ${target.className}")

                if (inserted) {
                    successfullyInserted = true
                    updateActiveTarget(target)
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Eccezione tentativo $attemptIndex inserimento testo: ${e.message}", e)
            } finally {
                if (needsRecycleTarget) {
                    try { target?.recycle() } catch (_: Exception) {}
                }
            }
        }

        // Salvagente universale appunti: NON copiare se era un campo password
        if (!isPasswordField) {
            copyToClipboard(newText)
        }

        if (!successfullyInserted) {
            Log.w(TAG, "Inserimento diretto non riuscito dopo tutti i tentativi.")
        }
        return isPasswordField
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

    private fun showOngoingNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
            val channelId = "openflow_persistent_service"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    "OpenFlow Servizio Attivo",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Mantiene attivo OpenFlow per la dettatura vocale continua"
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, com.openflow.android.ui.MainActivity::class.java)
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                }
            )

            val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
                .setSmallIcon(com.openflow.android.R.mipmap.ic_launcher)
                .setContentTitle("OpenFlow")
                .setContentText("Dettatura vocale IA pronta • Tocca per aprire")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_PERSISTENT_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_PERSISTENT_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Impossibile mostrare notifica persistente: ${e.message}")
        }
    }

    private fun cancelOngoingNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            notificationManager?.cancel(NOTIFICATION_PERSISTENT_ID)
        } catch (_: Exception) {}
    }

    private fun stopAudioRecordService() {
        try {
            stopService(Intent(this, AudioRecordService::class.java))
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {
        bubbleOverlay?.hide()
        audioRecorder.cancelRecording()
        stopAudioRecordService()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            bubbleOverlay?.hide()
        } else {
            bubbleOverlay?.updateNotchPosition()
            if (!prefs.showOnlyWhenFocused) {
                bubbleOverlay?.show()
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "FlowAccessibilityService onUnbind - abilitazione flag rebind per ripristino automatico")
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        bubbleOverlay?.hide()
        return true // Restituire true permette al sistema Android di invocare onRebind() senza errori
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        instance = this
        Log.i(TAG, "FlowAccessibilityService onRebind - riconnessione riuscita")
        showOngoingNotification()
        ensureOverlayInitialized()
        if (!prefs.showOnlyWhenFocused) {
            bubbleOverlay?.show()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.i(TAG, "onTrimMemory ricevuto: level=$level (alleggerimento risorse per fotocamera/multitasking)")
        if (level >= TRIM_MEMORY_MODERATE) {
            try { activeTargetNode?.recycle() } catch (_: Exception) {}
            activeTargetNode = null
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "onLowMemory ricevuto: pulizia cache target")
        try { activeTargetNode?.recycle() } catch (_: Exception) {}
        activeTargetNode = null
    }

    override fun onDestroy() {
        instance = null
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Exception) {}
        currentProcessingJob?.cancel()
        currentProcessingJob = null
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        bubbleOverlay?.destroy()
        audioRecorder.cancelRecording()
        stopAudioRecordService()
        cancelOngoingNotification()
        try { activeTargetNode?.recycle() } catch (_: Exception) {}
        activeTargetNode = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FlowAccessibilityService"
        private const val NOTIFICATION_PERSISTENT_ID = 1002

        var instance: FlowAccessibilityService? = null
            private set

        /**
         * Riavvia/riconnette chirurgicamente il servizio di accessibilità se l'app possiede
         * il permesso WRITE_SECURE_SETTINGS (concesso ad es. tramite ADB).
         * Risolve automaticamente il problema di HyperOS/MIUI "Servizio andato in errore".
         */
        fun restartAccessibilityServiceIfPermitted(context: Context): Boolean {
            val hasPermission = context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasPermission) return false

            return try {
                val serviceComponent = "${context.packageName}/${FlowAccessibilityService::class.java.canonicalName}"
                val currentSetting = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""

                // 1. Rimuove il servizio per pulire lo stato di errore/crashed da AccessibilityManager
                val filteredSetting = currentSetting.split(":")
                    .filter { it.isNotBlank() && it != serviceComponent }
                    .joinToString(":")

                android.provider.Settings.Secure.putString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    filteredSetting
                )

                // 2. Lo reinserisce dopo 350ms per forzare il re-bind pulito
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val newSetting = if (filteredSetting.isBlank()) serviceComponent else "$filteredSetting:$serviceComponent"
                    android.provider.Settings.Secure.putString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        newSetting
                    )
                    android.provider.Settings.Secure.putString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
                        "1"
                    )
                }, 350L)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Errore durante auto-restart del servizio: ${e.message}")
                false
            }
        }
    }
}
