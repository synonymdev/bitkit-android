package to.bitkit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import to.bitkit.ui.theme.Colors

private val ZigzagToothWidth = 24.dp
private val ZigzagHeight = 12.dp

/**
 * Torn paper top edge for note cards, drawn in the same fill as the card body.
 */
@Composable
fun ZigzagDivider(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(ZigzagHeight)
    ) {
        val zigzagWidth = ZigzagToothWidth.toPx()
        val amplitude = size.height
        val width = size.width
        val path = Path()

        path.moveTo(0f, 0f)
        var x = 0f
        while (x < width) {
            path.lineTo(x + zigzagWidth / 2, amplitude)
            path.lineTo((x + zigzagWidth).coerceAtMost(width), 0f)
            x += zigzagWidth
        }
        path.lineTo(width, amplitude)
        path.lineTo(0f, amplitude)
        path.close()

        drawPath(path = path, color = Colors.White10)
    }
}
