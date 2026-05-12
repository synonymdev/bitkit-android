package to.bitkit.appwidget.ui.components

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

object GlanceLayoutDimens {
    private val CELL = 70.dp

    val WIDE_LAYOUT_MIN_WIDTH = CELL * 3
    val TALL_LAYOUT_MIN_HEIGHT = CELL * 2

    val COMPACT_WIDGET_SIZE = DpSize(CELL * 2, CELL * 3)
    val WIDE_WIDGET_SIZE = DpSize(CELL * 4, CELL * 2)
}
