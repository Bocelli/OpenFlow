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
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import com.openflow.android.data.PreferencesManager
import kotlin.math.abs

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

    var currentState: BubbleState = BubbleState.IDLE
        private set

    // Coordinate & Drag
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

    fun show() {
        if (isViewAttached) {
            bubbleView?.visibility = View.VISIBLE
            return
        }

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // TYPE_ACCESSIBILITY_OVERLAY non richiede permessi extra se il servizio accessibilità è attivo!
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Posiziona la bolla comodamente sul bordo destro a metà altezza
            x = screenWidth - dpToPx(72f)
            y = screenHeight / 2 - dpToPx(30f)
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
            Log.d(TAG, "FloatingBubbleOverlay aggiunta con successo allo schermo!")
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante addView: ${e.message}", e)
            // Fallback su TYPE_APPLICATION_OVERLAY se per qualche motivo TYPE_ACCESSIBILITY fallisse
            try {
                params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                windowManager.addView(view, params)
                isViewAttached = true
            } catch (e2: Exception) {
                Log.e(TAG, "Errore fallback addView: ${e2.message}", e2)
            }
        }
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
                vibrate(35)
                handler.post(amplitudePollingRunnable)
            }
            BubbleState.PROCESSING -> {
                handler.removeCallbacks(amplitudePollingRunnable)
            }
            BubbleState.SUCCESS -> {
                vibrate(50)
                handler.removeCallbacks(amplitudePollingRunnable)
                handler.postDelayed({
                    updateState(BubbleState.IDLE)
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

                if (abs(dx) > dpToPx(6f) || abs(dy) > dpToPx(6f)) {
                    if (!isPushToTalkActive) {
                        isDragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    params.x = (initialX + dx).toInt()
                    params.y = (initialY + dy).toInt()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {}
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)

                if (isPushToTalkActive) {
                    isPushToTalkActive = false
                    updateState(BubbleState.PROCESSING)
                    onStopRecording()
                    return true
                }

                if (!isDragging) {
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
     * Vista personalizzata:
     * - Dimensioni calcolate dinamicamente in DP (visibile e nitida su qualsiasi risoluzione).
     * - Stile Wispr Dark (Nero profondo #0B0B0E, bordo neon violetto #7C3AED, waveform reattiva a 5 barre).
     */
    private inner class BubbleCustomView(context: Context) : View(context) {

        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#101115")
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#262832")
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(1.8f).toFloat()
        }

        private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EDEDEE")
            style = Paint.Style.FILL
            strokeCap = Paint.Cap.ROUND
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EDEDEE")
            textSize = dpToPx(18f).toFloat()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        private var state: BubbleState = BubbleState.IDLE
        private var normalizedAmp: Float = 0.15f
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
            val clamped = (raw / 32767f).coerceIn(0.12f, 1f)
            normalizedAmp = normalizedAmp * 0.35f + clamped * 0.65f
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val h = dpToPx(58f)
            val w = when (state) {
                BubbleState.RECORDING -> dpToPx(130f)
                BubbleState.PROCESSING -> dpToPx(90f)
                else -> dpToPx(58f)
            }
            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val strokeOffset = dpToPx(2f).toFloat()
            val rect = RectF(strokeOffset, strokeOffset, width - strokeOffset, height - strokeOffset)
            val radius = rect.height() / 2f

            when (state) {
                BubbleState.RECORDING -> borderPaint.color = Color.parseColor("#10B981") // Sottile accento smeraldo
                BubbleState.PROCESSING -> borderPaint.color = Color.parseColor("#3B3E4C") // Grigio titanio
                BubbleState.ERROR -> borderPaint.color = Color.parseColor("#EF4444") // Rosso discreto
                BubbleState.SUCCESS -> borderPaint.color = Color.parseColor("#10B981")
                else -> borderPaint.color = Color.parseColor("#262832") // Hairline minimale riposante
            }

            canvas.drawRoundRect(rect, radius, radius, backgroundPaint)
            canvas.drawRoundRect(rect, radius, radius, borderPaint)

            val cx = width / 2f
            val cy = height / 2f

            when (state) {
                BubbleState.IDLE -> {
                    drawMicIcon(canvas, cx, cy)
                }
                BubbleState.RECORDING -> {
                    val barWidth = dpToPx(3.5f).toFloat()
                    val spacing = dpToPx(7.5f).toFloat()

                    for (i in -2..2) {
                        val x = cx + (i * spacing)
                        val factor = 1f - (abs(i) * 0.22f)
                        val barHeight = ((height * 0.6f) * normalizedAmp * factor).coerceAtLeast(dpToPx(5f).toFloat())
                        val barRect = RectF(
                            x - barWidth / 2f,
                            cy - barHeight / 2f,
                            x + barWidth / 2f,
                            cy + barHeight / 2f
                        )
                        wavePaint.color = Color.parseColor(if (i == 0) "#34D399" else "#EDEDEE")
                        canvas.drawRoundRect(barRect, 4f, 4f, wavePaint)
                    }
                }
                BubbleState.PROCESSING -> {
                    canvas.save()
                    canvas.rotate(spinnerAngle, cx, cy)
                    val r = dpToPx(11f).toFloat()
                    val sweepRect = RectF(cx - r, cy - r, cx + r, cy + r)
                    val spinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#EDEDEE")
                        style = Paint.Style.STROKE
                        strokeWidth = dpToPx(2.2f).toFloat()
                        strokeCap = Paint.Cap.ROUND
                    }
                    canvas.drawArc(sweepRect, 0f, 270f, false, spinPaint)
                    canvas.restore()
                }
                BubbleState.SUCCESS -> {
                    canvas.drawText("✓", cx, cy + dpToPx(6f), textPaint.apply { color = Color.parseColor("#10B981") })
                }
                BubbleState.ERROR -> {
                    canvas.drawText("!", cx, cy + dpToPx(6f), textPaint.apply { color = Color.parseColor("#EF4444") })
                }
            }
        }

        private fun drawMicIcon(canvas: Canvas, cx: Float, cy: Float) {
            val micPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#EDEDEE")
                style = Paint.Style.FILL
            }
            val headW = dpToPx(5f).toFloat()
            val headH = dpToPx(10f).toFloat()
            val micHead = RectF(cx - headW, cy - headH, cx + headW, cy + headW)
            canvas.drawRoundRect(micHead, headW, headW, micPaint)

            val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#EDEDEE")
                style = Paint.Style.STROKE
                strokeWidth = dpToPx(2.0f).toFloat()
                strokeCap = Paint.Cap.ROUND
            }
            val arcR = dpToPx(9f).toFloat()
            val arcRect = RectF(cx - arcR, cy - dpToPx(5f), cx + arcR, cy + dpToPx(7f))
            canvas.drawArc(arcRect, 0f, 180f, false, arcPaint)

            canvas.drawLine(cx, cy + dpToPx(7f), cx, cy + dpToPx(12f), arcPaint)
            canvas.drawLine(cx - dpToPx(6f), cy + dpToPx(12f), cx + dpToPx(6f), cy + dpToPx(12f), arcPaint)
        }
    }

    companion object {
        private const val TAG = "FloatingBubbleOverlay"
    }
}
