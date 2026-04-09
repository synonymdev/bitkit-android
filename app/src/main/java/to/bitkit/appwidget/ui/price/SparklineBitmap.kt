package to.bitkit.appwidget.ui.price

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.annotation.ColorInt
import androidx.core.graphics.createBitmap

fun renderSparklineBitmap(
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

    fun xAt(index: Int) = padding + index * stepX
    fun yAt(value: Double) = padding + drawHeight - ((value - minValue) / range * drawHeight).toFloat()

    val linePath = Path().apply {
        moveTo(xAt(0), yAt(values[0]))
        for (i in 1 until values.size) {
            lineTo(xAt(i), yAt(values[i]))
        }
    }

    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    canvas.drawPath(linePath, linePaint)

    val fillPath = Path(linePath).apply {
        lineTo(xAt(values.size - 1), height.toFloat())
        lineTo(xAt(0), height.toFloat())
        close()
    }

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, padding,
            0f, height.toFloat(),
            (lineColor and 0x00FFFFFF) or 0xCC000000.toInt(),
            (lineColor and 0x00FFFFFF) or 0x4D000000.toInt(),
            Shader.TileMode.CLAMP,
        )
        style = Paint.Style.FILL
    }
    canvas.drawPath(fillPath, fillPaint)

    return bitmap
}
