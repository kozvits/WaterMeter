package com.watermeter.ui.add

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Накладывается поверх превью фото.
 * Рисует:
 *  - полупрозрачное затемнение вне круга
 *  - чёткую круглую рамку с угловыми маркерами
 *  - подсказку "Циферблат в круг"
 *
 * Используется как визуальный ориентир при съёмке.
 * Не влияет на обрезку/OCR — это только UI-подсказка.
 */
class MeterFrameOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // Основной круг: занимает 82% короткой стороны, центрирован
    private val circlePadding = 0.09f   // 9% отступ с каждой стороны

    // Краска для затемнения за пределами круга
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")  // 60% прозрачности
    }

    // Краска для "вырезания" круга из затемнения (Porter-Duff CLEAR)
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    // Внешняя рамка круга
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 3f
        alpha = 230
    }

    // Пунктирная внутренняя рамка
    private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 1.5f
        alpha = 140
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    // Угловые маркеры (4 дуги по 30° на каждом квадранте)
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#29B6F6")   // акцентный голубой
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }

    // Крестик в центре
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 1.5f
        alpha = 100
    }

    private val circleRect = RectF()
    private val innerRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val shortSide = minOf(w, h)
        val radius = shortSide * (1f - circlePadding * 2) / 2f
        val cx = w / 2f
        val cy = h / 2f

        circleRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // ── Слой затемнения ────────────────────────────────────────────────
        // Используем saveLayer чтобы CLEAR работал корректно
        val layerSave = canvas.saveLayer(0f, 0f, w, h, null)

        // Заливаем весь экран затемнением
        canvas.drawRect(0f, 0f, w, h, dimPaint)

        // Вырезаем круг (прозрачная область = циферблат виден чётко)
        canvas.drawCircle(cx, cy, radius, clearPaint)

        canvas.restoreToCount(layerSave)

        // ── Внешняя рамка ──────────────────────────────────────────────────
        canvas.drawCircle(cx, cy, radius, rimPaint)

        // ── Пунктирная внутренняя рамка (чуть меньше) ─────────────────────
        val innerRadius = radius - 10f
        innerRect.set(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius)
        canvas.drawOval(innerRect, dashedPaint)

        // ── Угловые маркеры — 4 дуги по 28° ───────────────────────────────
        val cornerRect = RectF(cx - radius - 2f, cy - radius - 2f,
                               cx + radius + 2f, cy + radius + 2f)
        val sweepAngle = 28f
        val startAngles = floatArrayOf(-104f, -14f, 76f, 166f)   // СЗ, СВ, ЮВ, ЮЗ
        for (start in startAngles) {
            canvas.drawArc(cornerRect, start, sweepAngle, false, cornerPaint)
        }

        // ── Центральный крестик ────────────────────────────────────────────
        val crossLen = 14f
        canvas.drawLine(cx - crossLen, cy, cx + crossLen, cy, crossPaint)
        canvas.drawLine(cx, cy - crossLen, cx, cy + crossLen, crossPaint)
    }
}
