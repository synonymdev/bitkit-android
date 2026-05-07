package to.bitkit.appwidget.ui.price

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.annotation.ColorInt
import androidx.core.graphics.createBitmap

private const val SMOOTHING = 0.2f

fun renderLineChartBitmap(
    values: List<Double>,
    width: Int,
    height: Int,
    @ColorInt lineColor: Int,
): Bitmap {
    val bitmap = createBitmap(width, height)
    if (values.size < 2) return bitmap

    val canvas = Canvas(bitmap)
    val minValue = values.min()
    val maxValue = values.max()
    val range = (maxValue - minValue).coerceAtLeast(1.0)

    val padding = 4f
    val drawWidth = width - padding * 2
    val drawHeight = height - padding * 2
    val stepX = drawWidth / (values.size - 1)

    val points = values.mapIndexed { i, v ->
        val x = padding + i * stepX
        val y = padding + drawHeight - ((v - minValue) / range * drawHeight).toFloat()
        x to y
    }

    val linePath = buildSmoothPath(points, yMin = padding, yMax = padding + drawHeight)

    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    canvas.drawPath(linePath, linePaint)

    return bitmap
}

private fun buildSmoothPath(
    points: List<Pair<Float, Float>>,
    yMin: Float,
    yMax: Float,
): Path = Path().apply {
    moveTo(points[0].first, points[0].second)
    for (i in 0 until points.size - 1) {
        val p0 = points[(i - 1).coerceAtLeast(0)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[(i + 2).coerceAtMost(points.lastIndex)]

        val cp1x = p1.first + (p2.first - p0.first) * SMOOTHING
        val cp1y = (p1.second + (p2.second - p0.second) * SMOOTHING).coerceIn(yMin, yMax)
        val cp2x = p2.first - (p3.first - p1.first) * SMOOTHING
        val cp2y = (p2.second - (p3.second - p1.second) * SMOOTHING).coerceIn(yMin, yMax)

        cubicTo(cp1x, cp1y, cp2x, cp2y, p2.first, p2.second)
    }
}
