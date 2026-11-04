package com.openflow.android.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import com.openflow.android.data.PreferencesManager
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
    private var isViewAttached = false

    var currentState: BubbleState = BubbleState.IDLE
        private set

    // Gestione Drag & Hybrid Touch (Tap vs Push-to-Talk)
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchDownTime = 0L
    private var isDragging = false
    private var isPushToTalkActive = false

    private val longPressRunnable = Runnable {
        if (!isDragging && currentState == BubbleState.IDLE) {
            isPushToTalkActive = true
            vibrate(40)
            currentState = BubbleState.RECORDING
            bubbleView?.updateState(BubbleState.RECORDING)
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

    fun show() {
        if (isViewAttached) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 48
            y = 220 // Si posiziona leggermente sopra la tastiera standard
        }

        layoutParams = params
        val view = BubbleCustomView(context).apply {
            setOnTouchListener { _, event -> handleTouchEvent(event) }
        }
        bubbleView = view

        try {
            windowManager.addView(view, params)
            isViewAttached = true
            updateState(BubbleState.IDLE)
        } catch (_: Exception) {}
    }

    fun hide() {
        if (!isViewAttached) return
        handler.removeCallbacks(amplitudePollingRunnable)
        handler.removeCallbacks(longPressRunnable)
        try {
            bubbleView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}
        isViewAttached = false
        bubbleView = null
    }

    fun updateState(state: BubbleState) {
        currentState = state
        bubbleView?.updateState(state)

        when (state) {
            BubbleState.RECORDING -> {
                vibrate(30)
                handler.post(amplitudePollingRunnable)
            }
            BubbleState.PROCESSING -> {
                handler.removeCallbacks(amplitudePollingRunnable)
            }
            BubbleState.SUCCESS -> {
                vibrate(50)
                handler.removeCallbacks(amplitudePollingRunnable)
                handler.postDelayed({
                    if (prefs.showOnlyWhenFocused) {
                        hide()
                    } else {
                        updateState(BubbleState.IDLE)
                    }
                }, 1200)
            }
            BubbleState.ERROR -> {
                vibrate(100)
                handler.removeCallbacks(amplitudePollingRunnable)
                handler.postDelayed({
                    updateState(BubbleState.IDLE)
                }, 1500)
            }
            BubbleState.IDLE -> {
                handler.removeCallbacks(amplitudePollingRunnable)
            }
        }
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        val params = layoutParams ?: return false
        val view = bubbleView ?: return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                touchDownTime = System.currentTimeMillis()
                isDragging = false
                isPushToTalkActive = false

                handler.postDelayed(longPressRunnable, 350)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                if (abs(dx) > 16 || abs(dy) > 16) {
                    if (!isPushToTalkActive) {
                        isDragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    // Inversione per Gravity.END/BOTTOM
                    params.x = (initialX - dx).toInt()
                    params.y = (initialY - dy).toInt()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {}
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)

                if (isPushToTalkActive) {
                    // Modalità Push-to-Talk terminata
                    isPushToTalkActive = false
                    updateState(BubbleState.PROCESSING)
                    onStopRecording()
                    return true
                }

                if (!isDragging) {
                    // Modalità Tap-to-toggle
                    val duration = System.currentTimeMillis() - touchDownTime
                    if (duration < 350) {
                        if (currentState == BubbleState.IDLE) {
                            updateState(BubbleState.RECORDING)
                            onStartRecording()
                        } else if (currentState == BubbleState.RECORDING) {
                            updateState(BubbleState.PROCESSING)
                            onStopRecording()
                        }
                    }
                }
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
     * Vista personalizzata renderizzata con hardware canvas:
     * Stile Wispr Dark (Nero OLED #0B0B0E, bordo neon violetto #7C3AED, waveform dinamica a 5 barre).
     */
    private inner class BubbleCustomView(context: Context) : View(context) {

        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F0F14")
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7C3AED")
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
        }

        private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#A855F7")
            style = Paint.Style.FILL
            strokeCap = Paint.Cap.ROUND
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F9FAFB")
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        private var state: BubbleState = BubbleState.IDLE
        private var normalizedAmp: Float = 0.1f
        private var spinnerAngle = 0f
        private val spinnerAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                spinnerAngle = it.animatedValue as Float
                invalidate()
            }
        }

        fun updateState(newState: BubbleState) {
            this.state = newState
            if (newState == BubbleState.PROCESSING) {
                spinnerAnimator.start()
            } else {
                spinnerAnimator.cancel()
            }
            invalidate()
            requestLayout()
        }

        fun setAmplitude(raw: Int) {
            val clamped = (raw / 32767f).coerceIn(0.1f, 1f)
            normalizedAmp = normalizedAmp * 0.4f + clamped * 0.6f
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val h = 130
            val w = when (state) {
                BubbleState.RECORDING -> 240
                BubbleState.PROCESSING -> 190
                else -> 130
            }
            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val rect = RectF(6f, 6f, width - 6f, height - 6f)
            val radius = rect.height() / 2f

            // Bordo dinamico per stato
            when (state) {
                BubbleState.RECORDING -> borderPaint.color = Color.parseColor("#10B981") // Verde Emerald
                BubbleState.PROCESSING -> borderPaint.color = Color.parseColor("#9333EA") // Viola Glow
                BubbleState.ERROR -> borderPaint.color = Color.parseColor("#EF4444") // Rosso
                BubbleState.SUCCESS -> borderPaint.color = Color.parseColor("#10B981")
                else -> borderPaint.color = Color.parseColor("#7C3AED") // Viola Wispr
            }

            canvas.drawRoundRect(rect, radius, radius, backgroundPaint)
            canvas.drawRoundRect(rect, radius, radius, borderPaint)

            val cx = width / 2f
            val cy = height / 2f

            when (state) {
                BubbleState.IDLE -> {
                    // Icona Microfono Wispr minimalista
                    drawMicIcon(canvas, cx, cy)
                }
                BubbleState.RECORDING -> {
                    // Waveform animata reattiva alla voce (5 barre)
                    val barWidth = 8f
                    val spacing = 14f
                    val startX = cx - (2 * spacing)

                    for (i in -2..2) {
                        val x = cx + (i * spacing)
                        val factor = 1f - (abs(i) * 0.2f)
                        val barHeight = ((height * 0.65f) * normalizedAmp * factor).coerceAtLeast(12f)
                        val barRect = RectF(
                            x - barWidth / 2f,
                            cy - barHeight / 2f,
                            x + barWidth / 2f,
                            cy + barHeight / 2f
                        )
                        wavePaint.color = Color.parseColor(if (i == 0) "#34D399" else "#A855F7")
                        canvas.drawRoundRect(barRect, 4f, 4f, wavePaint)
                    }
                }
                BubbleState.PROCESSING -> {
                    // Spinner rotante elegante
                    canvas.save()
                    canvas.rotate(spinnerAngle, cx, cy)
                    val sweepRect = RectF(cx - 24, cy - 24, cx + 24, cy + 24)
                    val spinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#C084FC")
                        style = Paint.Style.STROKE
                        strokeWidth = 5f
                        strokeCap = Paint.Cap.ROUND
                    }
                    canvas.drawArc(sweepRect, 0f, 270f, false, spinPaint)
                    canvas.restore()
                }
                BubbleState.SUCCESS -> {
                    // Checkmark verde
                    canvas.drawText("✓", cx, cy + 12, textPaint.apply { color = Color.parseColor("#10B981") })
                }
                BubbleState.ERROR -> {
                    canvas.drawText("!", cx, cy + 12, textPaint.apply { color = Color.parseColor("#EF4444") })
                }
            }
        }

        private fun drawMicIcon(canvas: Canvas, cx: Float, cy: Float) {
            val micPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E9D5FF")
                style = Paint.Style.FILL
            }
            // Testa microfono
            val micHead = RectF(cx - 10, cy - 22, cx + 10, cy + 6)
            canvas.drawRoundRect(micHead, 10f, 10f, micPaint)

            // Arco inferiore
            val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E9D5FF")
                style = Paint.Style.STROKE
                strokeWidth = 3.5f
                strokeCap = Paint.Cap.ROUND
            }
            val arcRect = RectF(cx - 16, cy - 12, cx + 16, cy + 12)
            canvas.drawArc(arcRect, 0f, 180f, false, arcPaint)

            // Gambo e base
            canvas.drawLine(cx, cy + 12, cx, cy + 20, arcPaint)
            canvas.drawLine(cx - 10, cy + 20, cx + 10, cy + 20, arcPaint)
        }
    }
}
