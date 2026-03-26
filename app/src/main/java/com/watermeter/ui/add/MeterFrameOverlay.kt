package com.watermeter.ui.add

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator

/**
 * Прямоугольный прицел для наведения на циферблат счётчика.
 *
 * Геометрия зоны сканирования:
 *   — ширина  = 88% экрана
 *   — высота  = 22% экрана  (узкая полоса — только одометр, без лишнего)
 *   — позиция = 38% от верха (чуть выше центра — удобно держать телефон)
 *
 * Эта же зона используется в MeterCameraActivity для кропа фото перед OCR.
 * Статический метод getScanRect() возвращает координаты зоны в px.
 */
class MeterFrameOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        const val RECT_WIDTH_RATIO  = 0.88f   // 88% ширины
        const val RECT_HEIGHT_RATIO = 0.22f   // 22% высоты
        const val RECT_TOP_RATIO    = 0.36f   // отступ сверху 36%

        /** Возвращает прямоугольник зоны OCR в координатах View */
        fun getScanRect(viewWidth: Int, viewHeight: Int): RectF {
            val w = viewWidth * RECT_WIDTH_RATIO
            val h = viewHeight * RECT_HEIGHT_RATIO
            val left = (viewWidth - w) / 2f
            val top  = viewHeight * RECT_TOP_RATIO
            return RectF(left, top, left + w, top + h)
        }
    }

    // ── Краски ────────────────────────────────────────────────────────────────

    private val dimPaint = Paint().apply {
        color = Color.parseColor("#B3000000")   // 70% затемнение
    }

    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.5f
        alpha = 220
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#29B6F6")
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    // Горизонтальные направляющие внутри зоны
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 0.8f
        alpha = 60
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    // Анимированная вертикальная линия сканирования
    private val scanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 190
    }

    private val scanRect = RectF()
    private var scanLineX = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            scanLineX = anim.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); animator.cancel() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scanRect.set(getScanRect(w, h))

        // Горизонтальный градиент для линии сканирования
        scanLinePaint.shader = LinearGradient(
            0f, 0f, 0f, scanRect.height(),
            intArrayOf(
                Color.TRANSPARENT,
                Color.parseColor("#8829B6F6"),
                Color.parseColor("#CC29B6F6"),
                Color.parseColor("#8829B6F6"),
                Color.TRANSPARENT
            ),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // ── Затемнение вне прямоугольника ────────────────────────────────
        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        canvas.drawRoundRect(scanRect, 4f, 4f, clearPaint)
        canvas.restoreToCount(layer)

        // ── Рамка прямоугольника ──────────────────────────────────────────
        canvas.drawRoundRect(scanRect, 4f, 4f, borderPaint)

        // ── Угловые маркеры (L-образные) ─────────────────────────────────
        val c = 24f
        val r = scanRect
        drawCorner(canvas, r.left,  r.top,    +c, +c)
        drawCorner(canvas, r.right, r.top,    -c, +c)
        drawCorner(canvas, r.left,  r.bottom, +c, -c)
        drawCorner(canvas, r.right, r.bottom, -c, -c)

        // ── Горизонтальные направляющие (деление зоны на трети) ──────────
        val third = scanRect.height() / 3f
        canvas.drawLine(r.left + 8f,  r.top + third,     r.right - 8f, r.top + third,     guidePaint)
        canvas.drawLine(r.left + 8f,  r.top + third * 2, r.right - 8f, r.top + third * 2, guidePaint)

        // ── Анимированная вертикальная линия ─────────────────────────────
        val lineX = scanRect.left + scanRect.width() * scanLineX
        if (lineX in scanRect.left..scanRect.right) {
            canvas.drawLine(lineX, scanRect.top, lineX, scanRect.bottom, scanLinePaint)
        }
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        canvas.drawLine(x, y, x + dx, y, cornerPaint)
        canvas.drawLine(x, y, x, y + dy, cornerPaint)
    }
}
