package com.watermeter.ui.add

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator

/**
 * Оверлей для BarcodeScannerActivity:
 * — затемнение вне рамки сканирования
 * — прямоугольная рамка с угловыми маркерами
 * — анимированная горизонтальная линия сканирования
 */
class ScannerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dimPaint = Paint().apply {
        color = Color.parseColor("#99000000")
    }

    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f
        alpha = 180
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#29B6F6")
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val scanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        alpha = 200
    }

    // Прямоугольник зоны сканирования
    private val scanRect = RectF()

    // Позиция анимированной линии (0..1 внутри scanRect)
    private var scanLinePosition = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            scanLinePosition = anim.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Зона сканирования: 80% по ширине, 35% по высоте, центрирована
        val rectW = w * 0.80f
        val rectH = h * 0.35f
        val cx = w / 2f
        val cy = h / 2f - h * 0.04f   // чуть выше центра
        scanRect.set(cx - rectW / 2f, cy - rectH / 2f, cx + rectW / 2f, cy + rectH / 2f)

        // Градиент для линии сканирования
        scanLinePaint.shader = LinearGradient(
            scanRect.left, 0f, scanRect.right, 0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.parseColor("#8029B6F6"),
                Color.parseColor("#CC29B6F6"),
                Color.parseColor("#8029B6F6"),
                Color.TRANSPARENT
            ),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // ── Затемнение вне рамки ───────────────────────────────────────
        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        canvas.drawRoundRect(scanRect, 8f, 8f, clearPaint)
        canvas.restoreToCount(layer)

        // ── Рамка ─────────────────────────────────────────────────────
        canvas.drawRoundRect(scanRect, 8f, 8f, rimPaint)

        // ── Угловые маркеры (L-образные линии) ────────────────────────
        val c = 28f   // длина уголка
        val r = scanRect
        // Верхний левый
        canvas.drawLine(r.left - 1f, r.top + c, r.left - 1f, r.top - 1f, cornerPaint)
        canvas.drawLine(r.left - 1f, r.top - 1f, r.left + c, r.top - 1f, cornerPaint)
        // Верхний правый
        canvas.drawLine(r.right + 1f, r.top + c, r.right + 1f, r.top - 1f, cornerPaint)
        canvas.drawLine(r.right + 1f, r.top - 1f, r.right - c, r.top - 1f, cornerPaint)
        // Нижний левый
        canvas.drawLine(r.left - 1f, r.bottom - c, r.left - 1f, r.bottom + 1f, cornerPaint)
        canvas.drawLine(r.left - 1f, r.bottom + 1f, r.left + c, r.bottom + 1f, cornerPaint)
        // Нижний правый
        canvas.drawLine(r.right + 1f, r.bottom - c, r.right + 1f, r.bottom + 1f, cornerPaint)
        canvas.drawLine(r.right + 1f, r.bottom + 1f, r.right - c, r.bottom + 1f, cornerPaint)

        // ── Анимированная линия сканирования ──────────────────────────
        val lineY = scanRect.top + scanRect.height() * scanLinePosition
        if (lineY in scanRect.top..scanRect.bottom) {
            canvas.drawLine(scanRect.left, lineY, scanRect.right, lineY, scanLinePaint)
        }
    }
}
