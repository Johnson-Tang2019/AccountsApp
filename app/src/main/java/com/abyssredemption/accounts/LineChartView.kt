package com.abyssredemption.accounts

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

data class ChartPoint(val label: String, val expense: Double, val balance: Double)

class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val points = mutableListOf<ChartPoint>()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F7DCE4")
        strokeWidth = 1f
    }
    private val expensePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E9829E")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val balancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4FAE82")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9B858C")
        textSize = 11f * resources.displayMetrics.scaledDensity
    }

    fun setPoints(values: List<ChartPoint>) {
        points.clear()
        points += values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + 10f
        val right = width - paddingRight - 10f
        val top = paddingTop + 12f
        val bottom = height - paddingBottom - 30f
        if (right <= left || bottom <= top) return

        repeat(4) { index ->
            val y = top + (bottom - top) * index / 3f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        if (points.isEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("暂无统计数据", width / 2f, (top + bottom) / 2f, textPaint)
            return
        }

        val maxExpense = points.maxOf { it.expense }.coerceAtLeast(1.0)
        val minBalance = points.minOf { it.balance }
        val maxBalance = points.maxOf { it.balance }
        val balanceRange = (maxBalance - minBalance).coerceAtLeast(1.0)
        drawSeries(canvas, left, right, top, bottom, expensePaint) { point ->
            point.expense / maxExpense
        }
        drawSeries(canvas, left, right, top, bottom, balancePaint) { point ->
            (point.balance - minBalance) / balanceRange
        }

        dotPaint.color = expensePaint.color
        drawDots(canvas, left, right, top, bottom) { it.expense / maxExpense }
        dotPaint.color = balancePaint.color
        drawDots(canvas, left, right, top, bottom) { (it.balance - minBalance) / balanceRange }

        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(points.first().label, left, height - paddingBottom - 8f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(points.last().label, right, height - paddingBottom - 8f, textPaint)
    }

    private fun drawSeries(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        paint: Paint,
        ratio: (ChartPoint) -> Double
    ) {
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = xAt(index, left, right)
            val y = yAt(ratio(point), top, bottom)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawDots(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        ratio: (ChartPoint) -> Double
    ) {
        points.forEachIndexed { index, point ->
            canvas.drawCircle(xAt(index, left, right), yAt(ratio(point), top, bottom), 4.2f, dotPaint)
        }
    }

    private fun xAt(index: Int, left: Float, right: Float): Float {
        if (points.size == 1) return (left + right) / 2f
        return left + (right - left) * index / (points.size - 1).toFloat()
    }

    private fun yAt(value: Double, top: Float, bottom: Float): Float {
        val safe = min(1.0, max(0.0, value)).toFloat()
        return bottom - (bottom - top) * safe
    }
}
