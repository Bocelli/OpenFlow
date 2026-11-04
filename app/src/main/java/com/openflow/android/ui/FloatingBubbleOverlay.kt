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

/**
 * Release C: Dynamic Island Notch-Docked Overlay (Universale).
 *
 * Invece di fluttuare ai lati dello schermo, la pillola è ancorata permanentemente attorno al punch-hole
 * della fotocamera frontale (Display Cutout) tramite WindowManager.
 * Si espande elasticamente verso destra e sinistra durante la dettatura e si ritrae a riposo.
 * Funziona al 100% su qualsiasi Android (Xiaomi, Samsung, Pixel, OnePlus, ecc.) senza dipendere da ROM o Root.
 */
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

    private var currentWidthPx = dpToPx(IDLE_WIDTH_DP)
    private var currentHeightPx = dpToPx(IDLE_HEIGHT_DP)

    // Touch & Gesture Tracking
    private var initialTouchY = 0f
    private var touchDownTime = 0L
    private var lastTapUpTime = 0L
    private var isPushToTalkActive = false
    private var isCancelCandidate = false

    private var widthAnimator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null

    private val stealthDimRunnable = Runnable {
        if (currentState == BubbleState.IDLE && prefs.stealthModeEnabled) {
            bubbleView?.animate()?.alpha(0.35f)?.setDuration(280)?.start()
        }
    }

    private val singleTapRunnable = Runnable {
        if (currentState == BubbleState.IDLE) {
            updateState(BubbleState.RECORDING)
            onStartRecording()
        }
    }

    private val longPressRunnable = Runnable {
        if (currentState == BubbleState.IDLE) {
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
                handler.postDelayed(this, 40)
            }
        }
    }

    private fun dpToPx(dp: Float): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }

    fun show(force: Boolean = false, fromTyping: Boolean = false) {
        fadeAnimator?.cancel()
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

        params.x = dpToPx(prefs.notchOffsetX)
        params.y = dpToPx(TOP_OFFSET_DP + prefs.notchOffsetY)
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {}
        view.invalidate()

        if (fromTyping) {
            cancelStealth()
        } else {
            scheduleStealth()
        }
    }

    fun hide() {
        if (!isViewAttached || !isVisible) return
        if (currentState == BubbleState.RECORDING || currentState == BubbleState.PROCESSING) {
            return
        }

        isVisible = false
        val view = bubbleView ?: return
        val params = layoutParams ?: return

        handler.removeCallbacks(amplitudePollingRunnable)
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(stealthDimRunnable)

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
                        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
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

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            currentWidthPx,
            currentHeightPx,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = dpToPx(prefs.notchOffsetX)
            y = dpToPx(TOP_OFFSET_DP + prefs.notchOffsetY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
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
            Log.d(TAG, "Notch Island overlay inizializzato con successo")
        } catch (e: Exception) {
            Log.e(TAG, "Errore addView overlay notch: ${e.message}", e)
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
        if (isViewAttached) {
            try {
                bubbleView?.let { windowManager.removeView(it) }
            } catch (_: Exception) {}
            isViewAttached = false
            bubbleView = null
        }
    }

    fun wakeUpFromStealth() {
        handler.removeCallbacks(stealthDimRunnable)
        if (!isVisible || !isViewAttached) {
            show(fromTyping = true)
            return
        }
        val view = bubbleView ?: return
        if (view.alpha < 0.95f) {
            view.animate().alpha(1.0f).setDuration(160).start()
        }
    }

    fun updateNotchPosition() {
        val params = layoutParams ?: return
        val view = bubbleView ?: return
        params.x = dpToPx(prefs.notchOffsetX)
        params.y = dpToPx(TOP_OFFSET_DP + prefs.notchOffsetY)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {}
        view.invalidate()
    }

    fun cancelStealth() {
        handler.removeCallbacks(stealthDimRunnable)
    }

    fun scheduleStealth(delayMs: Long = 3500) {
        handler.removeCallbacks(stealthDimRunnable)
        if (prefs.stealthModeEnabled && currentState == BubbleState.IDLE) {
            handler.postDelayed(stealthDimRunnable, delayMs)
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
                animateToSize(RECORDING_WIDTH_DP, RECORDING_HEIGHT_DP)
                handler.post(amplitudePollingRunnable)
            }
            BubbleState.PROCESSING -> {
                handler.removeCallbacks(stealthDimRunnable)
                bubbleView?.alpha = 1f
                handler.removeCallbacks(amplitudePollingRunnable)
                animateToSize(PROCESSING_WIDTH_DP, RECORDING_HEIGHT_DP)
            }
            BubbleState.SUCCESS -> {
                handler.removeCallbacks(stealthDimRunnable)
                bubbleView?.alpha = 1f
                vibrate(50)
                handler.removeCallbacks(amplitudePollingRunnable)
                animateToSize(SUCCESS_WIDTH_DP, RECORDING_HEIGHT_DP)
                handler.postDelayed({
                    updateState(BubbleState.IDLE)
                    if (prefs.showOnlyWhenFocused) {
                        hide()
                    }
                }, 1300)
            }
            BubbleState.ERROR -> {
                handler.removeCallbacks(stealthDimRunnable)
                bubbleView?.alpha = 1f
                vibrate(90)
                handler.removeCallbacks(amplitudePollingRunnable)
                animateToSize(SUCCESS_WIDTH_DP, RECORDING_HEIGHT_DP)
                handler.postDelayed({
                    updateState(BubbleState.IDLE)
                    if (prefs.showOnlyWhenFocused) {
                        hide()
                    }
                }, 1600)
            }
            BubbleState.IDLE -> {
                handler.removeCallbacks(amplitudePollingRunnable)
                animateToSize(IDLE_WIDTH_DP, IDLE_HEIGHT_DP)
                scheduleStealth()
            }
        }
    }

    /**
     * Espansione e contrazione fluida della pillola attorno al foro della fotocamera.
     */
    private fun animateToSize(targetWidthDp: Float, targetHeightDp: Float) {
        val params = layoutParams ?: return
        val view = bubbleView ?: return
        val startW = currentWidthPx
        val targetW = dpToPx(targetWidthDp)
        val startH = currentHeightPx
        val targetH = dpToPx(targetHeightDp)
        if (startW == targetW && startH == targetH) return

        widthAnimator?.cancel()
        widthAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                val w = (startW + (targetW - startW) * fraction).toInt()
                val h = (startH + (targetH - startH) * fraction).toInt()
                currentWidthPx = w
                currentHeightPx = h
                params.width = w
                params.height = h
                params.x = dpToPx(prefs.notchOffsetX)
                params.y = dpToPx(TOP_OFFSET_DP + prefs.notchOffsetY)
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: Exception) {}
                view.invalidate()
            }
            start()
        }
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        val view = bubbleView ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(stealthDimRunnable)
                view.alpha = 1f
                initialTouchY = event.rawY
                touchDownTime = SystemClock.uptimeMillis()
                isPushToTalkActive = false
                isCancelCandidate = false
                view.setCancelCandidate(false)

                view.animateScale(0.93f)
                vibrate(20)

                handler.postDelayed(longPressRunnable, 350)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - initialTouchY

                if (currentState == BubbleState.RECORDING) {
                    // Se trascina verso il basso (> 48dp), attiva l'annullamento
                    if (dy > dpToPx(48f)) {
                        if (!isCancelCandidate) {
                            isCancelCandidate = true
                            view.setCancelCandidate(true)
                            vibrate(25)
                        }
                    } else if (dy < dpToPx(28f)) {
                        if (isCancelCandidate) {
                            isCancelCandidate = false
                            view.setCancelCandidate(false)
                        }
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                view.animateScale(1.0f)
                handler.removeCallbacks(longPressRunnable)

                if (isCancelCandidate) {
                    isCancelCandidate = false
                    view.setCancelCandidate(false)
                    if (currentState == BubbleState.RECORDING) {
                        vibrate(60)
                        updateState(BubbleState.IDLE)
                        onCancelRecording()
                    }
                    scheduleStealth()
                    return true
                }

                if (isPushToTalkActive) {
                    isPushToTalkActive = false
                    if (currentState == BubbleState.RECORDING) {
                        updateState(BubbleState.PROCESSING)
                        onStopRecording()
                    }
                    scheduleStealth()
                    return true
                }

                val duration = SystemClock.uptimeMillis() - touchDownTime
                if (duration < 350) {
                    if (currentState == BubbleState.IDLE) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTapUpTime < 280) {
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
     * Vista Dynamic Island d'autore:
     * - Ancorata attorno al foro della fotocamera (punch-hole)
     * - IDLE: micro-capsula nera opaca attorno al sensore
     * - RECORDING: espansione alare a sinistra (equalizzatore vocale 5 barre) e a destra (timer + dot)
     * - PROCESSING: barra di progresso ritmica / breathing pulse
     * - SUCCESS: badge verde smeraldo di conferma
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
            color = Color.BLACK
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(1.4f).toFloat()
        }

        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(2.5f).toFloat()
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
            textSize = dpToPx(12f).toFloat()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8")
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(2f).toFloat()
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

            val strokeW = dpToPx(1.4f).toFloat()
            val inset = strokeW / 2f + dpToPx(2f).toFloat()
            val rect = RectF(inset, inset, w - inset, h - inset)
            val radius = rect.height() / 2f
            val cx = w / 2f
            val cy = h / 2f

            val now = SystemClock.uptimeMillis()
            val pulse = (sin((now % 1200) / 1200.0 * 2.0 * Math.PI) * 0.5 + 0.5).toFloat()

            // 1. Alone luminoso attorno al notch durante la registrazione
            if (state == BubbleState.RECORDING) {
                val haloInset = inset - dpToPx(1.5f + 2f * pulse)
                val haloRect = RectF(haloInset, haloInset, w - haloInset, h - haloInset)
                val haloRadius = haloRect.height() / 2f
                if (isCancelCandidate) {
                    haloPaint.color = Color.argb((50 + 60 * pulse).toInt(), 239, 68, 68)
                } else {
                    haloPaint.color = Color.argb((35 + 40 * pulse).toInt(), 56, 189, 248) // Sky blue
                }
                canvas.drawRoundRect(haloRect, haloRadius, haloRadius, haloPaint)
            }

            // 2. Sfondo Pillola Nero Assoluto OLED
            bgPaint.color = Color.BLACK
            canvas.drawRoundRect(rect, radius, radius, bgPaint)

            // 3. Bordo rifinito
            when (state) {
                BubbleState.RECORDING -> borderPaint.color = if (isCancelCandidate) Color.parseColor("#EF4444") else Color.parseColor("#38BDF8")
                BubbleState.PROCESSING -> borderPaint.color = Color.parseColor("#818CF8")
                BubbleState.SUCCESS -> borderPaint.color = Color.parseColor("#10B981")
                BubbleState.ERROR -> borderPaint.color = Color.parseColor("#EF4444")
                BubbleState.IDLE -> borderPaint.color = Color.parseColor("#27272A")
            }
            canvas.drawRoundRect(rect, radius, radius, borderPaint)

            // 4. Contenuti dinamici
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
                        statusTextPaint.textSize = dpToPx(10f).toFloat()
                        canvas.drawText(label, cx, cy + dpToPx(3.5f), statusTextPaint)
                    } else {
                        // Micro-punto rifinito che indica lo stile attivo
                        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.FILL
                            color = when (prefs.activePreset) {
                                PromptPreset.NATURAL -> Color.parseColor("#10B981")
                                PromptPreset.AI_PROMPT -> Color.parseColor("#818CF8")
                                PromptPreset.PROFESSIONAL -> Color.parseColor("#E4E4E7")
                                PromptPreset.BULLETS -> Color.parseColor("#F59E0B")
                            }
                        }
                        canvas.drawCircle(cx, cy + dpToPx(9f), dpToPx(1.6f).toFloat(), dotPaint)
                    }
                }

                BubbleState.RECORDING -> {
                    if (isCancelCandidate) {
                        statusTextPaint.color = Color.parseColor("#EF4444")
                        statusTextPaint.textSize = dpToPx(11f).toFloat()
                        canvas.drawText("✕ ANNULLA", cx, cy + dpToPx(4f), statusTextPaint)
                    } else {
                        // A sinistra della fotocamera (cx - 24dp indietro): Equalizzatore a 5 barre
                        val waveStartX = dpToPx(14f).toFloat()
                        val waveEndX = cx - dpToPx(20f)
                        val availableW = waveEndX - waveStartX
                        val numBars = 5
                        val barWidth = dpToPx(2.8f).toFloat()
                        val step = (availableW - barWidth) / (numBars - 1).coerceAtLeast(1).toFloat()
                        val maxBarHeight = h * 0.46f

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
                            wavePaint.color = if (i == 2) Color.parseColor("#38BDF8") else Color.parseColor("#E4E4E7")
                            canvas.drawRoundRect(barRect, 3f, 3f, wavePaint)
                        }

                        // Al centro: il foro della fotocamera (lasciato pulito e nero)

                        // A destra della fotocamera (cx + 20dp in avanti): Dot REC + Timer digitale
                        val recDotX = cx + dpToPx(24f)
                        val recDotR = dpToPx(3.2f).toFloat()
                        recDotPaint.color = Color.argb((150 + 105 * pulse).toInt(), 239, 68, 68)
                        canvas.drawCircle(recDotX, cy, recDotR, recDotPaint)

                        val elapsedSec = ((now - recordingStartTime) / 1000L).coerceAtLeast(0L)
                        val mm = elapsedSec / 60
                        val ss = elapsedSec % 60
                        val timeStr = String.format(Locale.US, "%02d:%02d", mm, ss)
                        canvas.drawText(timeStr, recDotX + dpToPx(6f), cy + dpToPx(4.2f), timerTextPaint)
                    }
                }

                BubbleState.PROCESSING -> {
                    // Spinner rotante + etichetta
                    val spinnerR = dpToPx(7f).toFloat()
                    val spinnerX = cx - dpToPx(40f)
                    val sweepRect = RectF(spinnerX - spinnerR, cy - spinnerR, spinnerX + spinnerR, cy + spinnerR)
                    canvas.save()
                    canvas.rotate(spinnerAngle, spinnerX, cy)
                    canvas.drawArc(sweepRect, 0f, 260f, false, spinnerPaint)
                    canvas.restore()

                    statusTextPaint.textSize = dpToPx(11f).toFloat()
                    statusTextPaint.color = Color.parseColor("#E4E4E7")
                    canvas.drawText("Trascrizione...", cx + dpToPx(10f), cy + dpToPx(3.8f), statusTextPaint)
                }

                BubbleState.SUCCESS -> {
                    statusTextPaint.textSize = dpToPx(11.5f).toFloat()
                    statusTextPaint.color = Color.parseColor("#10B981")
                    canvas.drawText("Testo inserito ✓", cx, cy + dpToPx(4f), statusTextPaint)
                }

                BubbleState.ERROR -> {
                    statusTextPaint.textSize = dpToPx(11.5f).toFloat()
                    statusTextPaint.color = Color.parseColor("#EF4444")
                    canvas.drawText("Errore ✕", cx, cy + dpToPx(4f), statusTextPaint)
                }
            }
        }
    }

    companion object {
        private const val TAG = "NotchIslandOverlay"
        private const val IDLE_WIDTH_DP = 33f
        private const val IDLE_HEIGHT_DP = 33f
        private const val RECORDING_WIDTH_DP = 216f
        private const val RECORDING_HEIGHT_DP = 34f
        private const val PROCESSING_WIDTH_DP = 152f
        private const val SUCCESS_WIDTH_DP = 142f
        private const val TOP_OFFSET_DP = 0f
    }
}
