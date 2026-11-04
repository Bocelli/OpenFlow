package com.openflow.android.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.openflow.android.data.PreferencesManager
import com.openflow.android.data.PromptPreset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

enum class BubbleState {
    IDLE,
    RECORDING,
    PROCESSING,
    SUCCESS,
    ERROR
}

@SuppressLint("ClickableViewAccessibility")
class FloatingBubbleOverlay(
    private val context: Context,
    private val prefs: PreferencesManager,
    private val onStartRecording: () -> Unit,
    private val onStopRecording: () -> Unit,
    private val onCancelRecording: () -> Unit,
    private val getAmplitude: () -> Int
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val handler = Handler(Looper.getMainLooper())

    private var bubbleView: BubbleCustomView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    var isViewAttached = false
        private set

    var isVisible = false
        private set

    var currentState: BubbleState = BubbleState.IDLE
        private set

    private var currentWidthPx = dpToPx(IDLE_SIZE_DP)
    private var currentHeightPx = dpToPx(HEIGHT_DP)
    private var isDockedOnRight = true

    // Coordinate & Drag
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchDownTime = 0L
    private var lastTapUpTime = 0L
    private var isDragging = false
    private var isPushToTalkActive = false
    private var isCancelCandidate = false

    private var widthAnimator: ValueAnimator? = null
    private var snapAnimator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null

    private val stealthDimRunnable = Runnable {
        if (currentState == BubbleState.IDLE && prefs.stealthModeEnabled) {
            bubbleView?.animate()?.alpha(0.28f)?.setDuration(280)?.start()
        }
    }

    private val singleTapRunnable = Runnable {
        if (currentState == BubbleState.IDLE) {
            updateState(BubbleState.RECORDING)
            onStartRecording()
        }
    }

    private val longPressRunnable = Runnable {
        if (!isDragging && currentState == BubbleState.IDLE) {
            isPushToTalkActive = true
            vibrate(40)
            updateState(BubbleState.RECORDING)
            onStartRecording()
        }
    }

    private val amplitudePollingRunnable = object : Runnable {
        override fun run() {
            if (currentState == BubbleState.RECORDING) {
                val amp = getAmplitude()
                bubbleView?.setAmplitude(amp)
                handler.postDelayed(this, 50)
            }
        }
    }

    private fun dpToPx(dp: Float): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }

    fun show(force: Boolean = false) {
        if (!isViewAttached) {
            initWindow()
        }
        val view = bubbleView ?: return
        val params = layoutParams ?: return

        isVisible = true
        view.visibility = View.VISIBLE
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f

        // Rende la finestra toccabile sui suoi confini effettivi
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {}
        view.invalidate()
        scheduleStealth()
    }

    fun hide() {
        if (!isViewAttached || !isVisible) return
        // NON nascondere mai se c'è una registrazione vocale o elaborazione in corso
        if (currentState == BubbleState.RECORDING || currentState == BubbleState.PROCESSING) {
            return
        }

        isVisible = false
        val view = bubbleView ?: return
        val params = layoutParams ?: return

        handler.removeCallbacks(amplitudePollingRunnable)
        handler.removeCallbacks(longPressRunnable)

        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                view.alpha = f
                view.scaleX = 0.85f + 0.15f * f
                view.scaleY = 0.85f + 0.15f * f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isVisible) {
                        view.visibility = View.GONE
                        // Quando nascosta, imposta FLAG_NOT_TOUCHABLE per garantire che il 100% degli input
                        // dell'utente passi indisturbato alle app sottostanti senza bloccare il touch.
                        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (_: Exception) {}
                    }
                }
            })
            start()
        }
    }

    private fun initWindow() {
        if (isViewAttached) return

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        currentWidthPx = dpToPx(IDLE_SIZE_DP)
        currentHeightPx = dpToPx(HEIGHT_DP)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Posiziona comodamente la bolla sul bordo destro con margine
        isDockedOnRight = true
        val margin = dpToPx(MARGIN_DP)
        val initialXPos = screenWidth - currentWidthPx - margin
        val initialYPos = (screenHeight * 0.52f).toInt()

        val params = WindowManager.LayoutParams(
            currentWidthPx,
            currentHeightPx,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialXPos
            y = initialYPos
        }

        layoutParams = params
        val view = BubbleCustomView(context).apply {
            setOnTouchListener { _, event -> handleTouchEvent(event) }
        }
        bubbleView = view

        try {
            windowManager.addView(view, params)
            isViewAttached = true
            isVisible = true
            updateState(BubbleState.IDLE)
            Log.d(TAG, "FloatingBubbleOverlay initWindow creata con successo")
        } catch (e: Exception) {
            Log.e(TAG, "Errore addView overlay: ${e.message}", e)
            try {
                params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                windowManager.addView(view, params)
                isViewAttached = true
                isVisible = true
            } catch (e2: Exception) {
                Log.e(TAG, "Errore fallback addView: ${e2.message}", e2)
            }
        }
    }

    fun destroy() {
        handler.removeCallbacks(amplitudePollingRunnable)
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(stealthDimRunnable)
        handler.removeCallbacks(singleTapRunnable)
        fadeAnimator?.cancel()
        widthAnimator?.cancel()
        snapAnimator?.cancel()
        if (isViewAttached) {
            try {
                bubbleView?.let { windowManager.removeView(it) }
            } catch (_: Exception) {}
            isViewAttached = false
            bubbleView = null
        }
    }

    private fun scheduleStealth() {
        handler.removeCallbacks(stealthDimRunnable)
        if (prefs.stealthModeEnabled && currentState == BubbleState.IDLE) {
            handler.postDelayed(stealthDimRunnable, 3500)
        }
    }

    private fun cyclePreset() {
        val presets = PromptPreset.values()
        val currentIdx = presets.indexOf(prefs.activePreset)
        val nextPreset = presets[(currentIdx + 1) % presets.size]
        prefs.activePreset = nextPreset
        vibrate(35)
        bubbleView?.flashPreset(nextPreset)
        scheduleStealth()
    }

    fun updateState(state: BubbleState) {
        currentState = state
        bubbleView?.updateState(state)

        when (state) {
            BubbleState.RECORDING -> {
                handler.removeCallbacks(stealthDimRunnable)
                bubbleView?.alpha = 1f
                vibrate(35)
                animateToWidth(RECORDING_WIDTH_DP)
                handler.post(amplitudePollingRunnable)
            }
            BubbleState.PROCESSING -> {
                handler.removeCallbacks(stealthDimRunnable)
                bubbleView?.alpha = 1f
                handler.removeCallbacks(amplitudePollingRunnable)
                animateToWidth(PROCESSING_WIDTH_DP)
            }
            BubbleState.SUCCESS -> {
                handler.removeCallbacks(stealthDimRunnable)
                bubbleView?.alpha = 1f
                vibrate(50)
                handler.removeCallbacks(amplitudePollingRunnable)
                animateToWidth(IDLE_SIZE_DP)
                handler.postDelayed({
                    updateState(BubbleState.IDLE)
                    if (prefs.showOnlyWhenFocused) {
                        hide()
                    }
                }, 1200)
            }
            BubbleState.ERROR -> {
                handler.removeCallbacks(stealthDimRunnable)
                bubbleView?.alpha = 1f
                vibrate(90)
                handler.removeCallbacks(amplitudePollingRunnable)
                animateToWidth(IDLE_SIZE_DP)
                handler.postDelayed({
                    updateState(BubbleState.IDLE)
                }, 1500)
            }
            BubbleState.IDLE -> {
                handler.removeCallbacks(amplitudePollingRunnable)
                animateToWidth(IDLE_SIZE_DP)
                scheduleStealth()
            }
        }
    }

    /**
     * Espansione e contrazione fluida della bolla in pillola vocale.
     * Se ancorata a destra, si espande elegantemente verso sinistra senza uscire dallo schermo.
     */
    private fun animateToWidth(targetWidthDp: Float) {
        val params = layoutParams ?: return
        val view = bubbleView ?: return
        val startW = currentWidthPx
        val targetW = dpToPx(targetWidthDp)
        if (startW == targetW) return

        val screenWidth = context.resources.displayMetrics.widthPixels
        val margin = dpToPx(MARGIN_DP)

        widthAnimator?.cancel()
        widthAnimator = ValueAnimator.ofInt(startW, targetW).apply {
            duration = 230
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { anim ->
                val w = anim.animatedValue as Int
                currentWidthPx = w
                params.width = w
                if (isDockedOnRight) {
                    params.x = screenWidth - w - margin
                } else {
                    params.x = margin
                }
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: Exception) {}
                view.invalidate()
            }
            start()
        }
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        val params = layoutParams ?: return false
        val view = bubbleView ?: return false
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val margin = dpToPx(MARGIN_DP)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(stealthDimRunnable)
                view.alpha = 1f
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                touchDownTime = SystemClock.uptimeMillis()
                isDragging = false
                isPushToTalkActive = false
                isCancelCandidate = false
                view.setCancelCandidate(false)

                view.animateScale(0.93f)
                vibrate(20)

                handler.postDelayed(longPressRunnable, 350)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                if (currentState == BubbleState.RECORDING) {
                    if (dy > dpToPx(65f)) {
                        if (!isCancelCandidate) {
                            isCancelCandidate = true
                            view.setCancelCandidate(true)
                            vibrate(25)
                        }
                    } else if (dy < dpToPx(40f)) {
                        if (isCancelCandidate) {
                            isCancelCandidate = false
                            view.setCancelCandidate(false)
                        }
                    }
                    return true
                }

                if (!isDragging && (abs(dx) > dpToPx(6f) || abs(dy) > dpToPx(6f))) {
                    if (!isPushToTalkActive) {
                        isDragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                }

                if (isDragging) {
                    params.x = (initialX + dx).toInt().coerceIn(0, screenWidth - currentWidthPx)
                    params.y = (initialY + dy).toInt().coerceIn(dpToPx(24f), screenHeight - currentHeightPx - dpToPx(24f))
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {}
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                view.animateScale(1.0f)
                handler.removeCallbacks(longPressRunnable)

                if (currentState == BubbleState.RECORDING && isCancelCandidate) {
                    isCancelCandidate = false
                    view.setCancelCandidate(false)
                    isPushToTalkActive = false
                    vibrate(40)
                    updateState(BubbleState.IDLE)
                    onCancelRecording()
                    return true
                }

                if (isPushToTalkActive) {
                    isPushToTalkActive = false
                    updateState(BubbleState.PROCESSING)
                    onStopRecording()
                    return true
                }

                if (isDragging) {
                    // Calcola il bordo più vicino per un magnetismo naturale
                    val bubbleCenterX = params.x + currentWidthPx / 2
                    isDockedOnRight = bubbleCenterX >= screenWidth / 2
                    val targetX = if (isDockedOnRight) {
                        screenWidth - currentWidthPx - margin
                    } else {
                        margin
                    }

                    snapAnimator?.cancel()
                    snapAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
                        duration = 200
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { anim ->
                            params.x = anim.animatedValue as Int
                            try {
                                windowManager.updateViewLayout(view, params)
                            } catch (_: Exception) {}
                        }
                        start()
                    }
                    scheduleStealth()
                    return true
                }

                // Tocco rapido (Tap)
                val duration = SystemClock.uptimeMillis() - touchDownTime
                if (duration < 350) {
                    if (currentState == BubbleState.IDLE) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTapUpTime < 280) {
                            // Doppio Tocco: Ciclo Stile Prompt
                            handler.removeCallbacks(singleTapRunnable)
                            lastTapUpTime = 0L
                            cyclePreset()
                        } else {
                            lastTapUpTime = now
                            handler.postDelayed(singleTapRunnable, 230)
                        }
                    } else if (currentState == BubbleState.RECORDING) {
                        updateState(BubbleState.PROCESSING)
                        onStopRecording()
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                view.animateScale(1.0f)
                handler.removeCallbacks(longPressRunnable)
                if (isCancelCandidate) {
                    isCancelCandidate = false
                    view.setCancelCandidate(false)
                    if (currentState == BubbleState.RECORDING) {
                        updateState(BubbleState.IDLE)
                        onCancelRecording()
                    }
                }
                scheduleStealth()
                return true
            }
        }
        return false
    }

    private fun vibrate(ms: Long) {
        if (!prefs.hapticFeedbackEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(ms)
            }
        } catch (_: Exception) {}
    }

    /**
     * Vista d'autore Wispr Flow / Swiss Minimalist:
     * - In IDLE: badge acustico compatto (56x56) con glifo a 3 pilastri armonici.
     * - In RECORDING: pillola vocale estesa (142x56) in stile messaggio vocale:
     *   • Alone pulsante di ascolto (aura traslucida smeraldo)
     *   • Indicatore di registrazione rosso/smeraldo a respiro + Timer numerico in tempo reale (00:03)
     *   • Equalizzatore reattivo a 5 barre dinamiche danzanti sul microfono reale
     * - In PROCESSING: micro spinner essenziale in titanio
     * - In SUCCESS/ERROR: checkmark o alert discreto
     */
    private inner class BubbleCustomView(context: Context) : View(context) {

        private var state: BubbleState = BubbleState.IDLE
        private var normalizedAmp: Float = 0.15f
        private var spinnerAngle = 0f
        private var recordingStartTime = 0L
        private var isCancelCandidate = false
        private var flashedPreset: PromptPreset? = null

        private val clearPresetRunnable = Runnable {
            flashedPreset = null
            invalidate()
        }

        fun setCancelCandidate(candidate: Boolean) {
            if (isCancelCandidate != candidate) {
                isCancelCandidate = candidate
                invalidate()
            }
        }

        fun flashPreset(preset: PromptPreset) {
            flashedPreset = preset
            handler.removeCallbacks(clearPresetRunnable)
            handler.postDelayed(clearPresetRunnable, 1000)
            invalidate()
        }

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0C0D10")
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(1.6f).toFloat()
        }

        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(3f).toFloat()
        }

        private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            strokeCap = Paint.Cap.ROUND
        }

        private val recDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val timerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E4E4E7")
            textSize = dpToPx(12f).toFloat()
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }

        private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dpToPx(20f).toFloat()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D4D4D8")
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(2.2f).toFloat()
            strokeCap = Paint.Cap.ROUND
        }

        private val spinnerAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                spinnerAngle = it.animatedValue as Float
                invalidate()
            }
        }

        fun animateScale(targetScale: Float) {
            animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(120)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        fun updateState(newState: BubbleState) {
            this.state = newState
            if (newState == BubbleState.RECORDING) {
                recordingStartTime = SystemClock.uptimeMillis()
                spinnerAnimator.cancel()
            } else if (newState == BubbleState.PROCESSING) {
                spinnerAnimator.start()
            } else {
                spinnerAnimator.cancel()
            }
            invalidate()
        }

        fun setAmplitude(raw: Int) {
            val clamped = (raw / 32767f).coerceIn(0.12f, 1f)
            normalizedAmp = normalizedAmp * 0.35f + clamped * 0.65f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val strokeW = dpToPx(1.6f).toFloat()
            val inset = strokeW / 2f + dpToPx(3f).toFloat()
            val rect = RectF(inset, inset, w - inset, h - inset)
            val radius = rect.height() / 2f
            val cx = w / 2f
            val cy = h / 2f

            val now = SystemClock.uptimeMillis()
            val pulse = (sin((now % 1200) / 1200.0 * 2.0 * Math.PI) * 0.5 + 0.5).toFloat()

            // 1. Alone pulsante (halo breath) durante la registrazione
            if (state == BubbleState.RECORDING) {
                val haloInset = inset - dpToPx(1.2f + 2.2f * pulse)
                val haloRect = RectF(haloInset, haloInset, w - haloInset, h - haloInset)
                val haloRadius = haloRect.height() / 2f
                if (isCancelCandidate) {
                    haloPaint.color = Color.argb((50 + 60 * pulse).toInt(), 239, 68, 68)
                } else {
                    haloPaint.color = Color.argb((35 + 40 * pulse).toInt(), 16, 185, 129) // Emerald traslucido
                }
                canvas.drawRoundRect(haloRect, haloRadius, haloRadius, haloPaint)
            }

            // 2. Sfondo Pillola Obsidian
            bgPaint.color = Color.parseColor("#0C0D10")
            canvas.drawRoundRect(rect, radius, radius, bgPaint)

            // 3. Bordo Hairline minimale
            when (state) {
                BubbleState.RECORDING -> borderPaint.color = if (isCancelCandidate) Color.parseColor("#EF4444") else Color.parseColor("#10B981")
                BubbleState.PROCESSING -> borderPaint.color = Color.parseColor("#3F3F46")
                BubbleState.SUCCESS -> borderPaint.color = Color.parseColor("#10B981")
                BubbleState.ERROR -> borderPaint.color = Color.parseColor("#EF4444")
                BubbleState.IDLE -> borderPaint.color = Color.parseColor("#27272A")
            }
            canvas.drawRoundRect(rect, radius, radius, borderPaint)

            // 4. Contenuto per stato
            when (state) {
                BubbleState.IDLE -> {
                    val p = flashedPreset
                    if (p != null) {
                        val label = when (p) {
                            PromptPreset.NATURAL -> "WISPR"
                            PromptPreset.AI_PROMPT -> "PROMPT"
                            PromptPreset.PROFESSIONAL -> "PRO"
                            PromptPreset.BULLETS -> "LIST"
                        }
                        val accentColor = when (p) {
                            PromptPreset.NATURAL -> Color.parseColor("#10B981")
                            PromptPreset.AI_PROMPT -> Color.parseColor("#818CF8")
                            PromptPreset.PROFESSIONAL -> Color.parseColor("#E4E4E7")
                            PromptPreset.BULLETS -> Color.parseColor("#F59E0B")
                        }
                        statusTextPaint.color = accentColor
                        statusTextPaint.textSize = dpToPx(11f).toFloat()
                        canvas.drawText(label, cx, cy + dpToPx(4f), statusTextPaint)
                    } else {
                        drawFlowGlyph(canvas, cx, cy)
                    }
                }

                BubbleState.RECORDING -> {
                    if (isCancelCandidate) {
                        // Feedback visivo immediato di cancellazione trascinando verso il basso
                        statusTextPaint.color = Color.parseColor("#EF4444")
                        statusTextPaint.textSize = dpToPx(11.5f).toFloat()
                        canvas.drawText("✕ RILASCIA PER ANNULLARE", cx, cy + dpToPx(4f), statusTextPaint)
                    } else {
                        // Sinistra: Indicatore REC a respiro + Timer numerico (00:03)
                        val recDotX = dpToPx(15f).toFloat()
                        val recDotR = dpToPx(3.6f).toFloat()
                        recDotPaint.color = Color.argb((150 + 105 * pulse).toInt(), 239, 68, 68) // Rosso vivido pulsante
                        canvas.drawCircle(recDotX, cy, recDotR, recDotPaint)

                        val elapsedSec = ((now - recordingStartTime) / 1000L).coerceAtLeast(0L)
                        val mm = elapsedSec / 60
                        val ss = elapsedSec % 60
                        val timeStr = String.format(Locale.US, "%02d:%02d", mm, ss)
                        val textY = cy + dpToPx(4.2f)
                        canvas.drawText(timeStr, recDotX + dpToPx(6.5f), textY, timerTextPaint)

                        // Destra: Equalizzatore reattivo a 5 barre dinamiche
                        val waveStartX = dpToPx(66f).toFloat()
                        val waveEndX = w - dpToPx(14f)
                        val availableW = waveEndX - waveStartX
                        val numBars = 5
                        val barWidth = dpToPx(3.2f).toFloat()
                        val step = (availableW - barWidth) / (numBars - 1).coerceAtLeast(1)

                        val maxBarHeight = h * 0.50f

                        for (i in 0 until numBars) {
                            val bx = waveStartX + i * step + barWidth / 2f
                            val distFromCenter = abs(i - (numBars - 1) / 2f)
                            val shapeFactor = 1.0f - (distFromCenter * 0.28f)
                            val barH = (maxBarHeight * normalizedAmp * shapeFactor).coerceAtLeast(dpToPx(4f).toFloat())

                            val barRect = RectF(
                                bx - barWidth / 2f,
                                cy - barH / 2f,
                                bx + barWidth / 2f,
                                cy + barH / 2f
                            )
                            wavePaint.color = if (i == 2) Color.parseColor("#34D399") else Color.parseColor("#E4E4E7")
                            canvas.drawRoundRect(barRect, 4f, 4f, wavePaint)
                        }
                    }
                }

                BubbleState.PROCESSING -> {
                    canvas.save()
                    canvas.rotate(spinnerAngle, cx, cy)
                    val r = dpToPx(10f).toFloat()
                    val sweepRect = RectF(cx - r, cy - r, cx + r, cy + r)
                    canvas.drawArc(sweepRect, 0f, 260f, false, spinnerPaint)
                    canvas.restore()
                }

                BubbleState.SUCCESS -> {
                    statusTextPaint.textSize = dpToPx(20f).toFloat()
                    statusTextPaint.color = Color.parseColor("#10B981")
                    canvas.drawText("✓", cx, cy + dpToPx(7f), statusTextPaint)
                }

                BubbleState.ERROR -> {
                    statusTextPaint.textSize = dpToPx(20f).toFloat()
                    statusTextPaint.color = Color.parseColor("#EF4444")
                    canvas.drawText("!", cx, cy + dpToPx(7f), statusTextPaint)
                }
            }
        }

        private fun drawFlowGlyph(canvas: Canvas, cx: Float, cy: Float) {
            val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
            val barW = dpToPx(3.4f).toFloat()
            val spacing = dpToPx(7.2f).toFloat()
            val radius = barW / 2f

            val bars = listOf(
                Triple(-1f, dpToPx(11f).toFloat(), "#71717A"),
                Triple(0f, dpToPx(19f).toFloat(), "#F4F4F5"),
                Triple(1f, dpToPx(13f).toFloat(), "#71717A")
            )

            for ((pos, h, colorHex) in bars) {
                glyphPaint.color = Color.parseColor(colorHex)
                val x = cx + (pos * spacing)
                val rect = RectF(
                    x - barW / 2f,
                    cy - h / 2f,
                    x + barW / 2f,
                    cy + h / 2f
                )
                canvas.drawRoundRect(rect, radius, radius, glyphPaint)
            }

            // Micro punto indicatore colore preset attivo (Swiss design detail)
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = when (prefs.activePreset) {
                    PromptPreset.NATURAL -> Color.parseColor("#10B981")
                    PromptPreset.AI_PROMPT -> Color.parseColor("#818CF8")
                    PromptPreset.PROFESSIONAL -> Color.parseColor("#E4E4E7")
                    PromptPreset.BULLETS -> Color.parseColor("#F59E0B")
                }
            }
            canvas.drawCircle(cx, cy + dpToPx(13.5f), dpToPx(1.6f).toFloat(), dotPaint)
        }
    }

    companion object {
        private const val TAG = "FloatingBubbleOverlay"
        private const val IDLE_SIZE_DP = 56f
        private const val RECORDING_WIDTH_DP = 142f
        private const val PROCESSING_WIDTH_DP = 94f
        private const val HEIGHT_DP = 56f
        private const val MARGIN_DP = 12f
    }
}
