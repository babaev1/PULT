// app/src/main/java/com/example/roki_pult/JoystickView.kt
package com.example.roki_pult

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnMoveListener {
        fun onMove(x: Int, y: Int)
    }

    private var onMoveListener: OnMoveListener? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#A5D6A7")
    }

    private val trenchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#81C784")
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val disabledThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#9E9E9E")
    }

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var thumbRadius = 0f
    private var trenchRadius = 0f

    private val arrowPath = Path()

    private var touchX = 0f
    private var touchY = 0f

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setWillNotDraw(false)
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val size = if (widthMode == MeasureSpec.EXACTLY && heightMode == MeasureSpec.EXACTLY) {
            min(widthSize, heightSize)
        } else if (widthSize > 0 && heightSize > 0) {
            min(widthSize, heightSize)
        } else {
            600 // Гарантированный размер, если система не передала данные
        }
        setMeasuredDimension(size, size)
    }

    fun setOnMoveListener(listener: OnMoveListener) {
        onMoveListener = listener
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = min(w, h)
        centerX = w / 2f
        centerY = h / 2f
        baseRadius = size / 2.2f
        trenchRadius = baseRadius * 0.75f
        thumbRadius = trenchRadius * 0.8f

        touchX = centerX
        touchY = centerY

        val arrowSize = baseRadius * 0.1f
        val arrowYPos = centerY - trenchRadius - (baseRadius - trenchRadius) / 2
        arrowPath.reset()
        arrowPath.moveTo(centerX, arrowYPos - arrowSize)
        arrowPath.lineTo(centerX - arrowSize, arrowYPos + arrowSize)
        arrowPath.lineTo(centerX + arrowSize, arrowYPos + arrowSize)
        arrowPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Debug frame for visibility
        val debugPaint = Paint().apply {
            color = Color.parseColor("#33FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), debugPaint)

        if (isInEditMode) {
            val previewPaint = Paint().apply { color = Color.LTGRAY; style = Paint.Style.FILL }
            canvas.drawCircle(width / 2f, height / 2f, width / 2.2f, previewPaint)
            return
        }

        canvas.drawCircle(centerX, centerY, baseRadius, basePaint)
        canvas.drawCircle(centerX, centerY, trenchRadius, trenchPaint)

        canvas.save()
        for (i in 0..3) {
            canvas.drawPath(arrowPath, arrowPaint)
            canvas.rotate(90f, centerX, centerY)
        }
        canvas.restore()

        if (isEnabled) {
            thumbPaint.shader = RadialGradient(
                touchX, touchY - thumbRadius * 0.1f,
                thumbRadius * 1.5f,
                intArrayOf(Color.WHITE, Color.parseColor("#C8E6C9"), Color.parseColor("#A5D6A7")),
                floatArrayOf(0f, 0.5f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(touchX, touchY, thumbRadius, thumbPaint)
        } else {
            canvas.drawCircle(centerX, centerY, thumbRadius, disabledThumbPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        val displacementX = event.x - centerX
        val displacementY = event.y - centerY
        val distance = sqrt(displacementX.pow(2) + displacementY.pow(2))

        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                if (distance < trenchRadius) {
                    touchX = event.x
                    touchY = event.y
                } else {
                    touchX = centerX + displacementX / distance * trenchRadius
                    touchY = centerY + displacementY / distance * trenchRadius
                }
                calculateAndDispatchMove()
            }
            MotionEvent.ACTION_UP -> {
                touchX = centerX
                touchY = centerY
                calculateAndDispatchMove()
            }
        }
        invalidate()
        return true
    }

    private fun calculateAndDispatchMove() {
        val dx = touchX - centerX
        val dy = touchY - centerY
        val x = (dx / trenchRadius * 127).toInt().coerceIn(-127, 127)
        val y = -(dy / trenchRadius * 127).toInt().coerceIn(-127, 127)
        onMoveListener?.onMove(x, y)
    }
}