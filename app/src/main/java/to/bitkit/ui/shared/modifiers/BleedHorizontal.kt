package to.bitkit.ui.shared.modifiers

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp

/** Widens the element by [padding] on each side, so it spans the parent's horizontal padding. */
fun Modifier.bleedHorizontal(padding: Dp): Modifier = layout { measurable, constraints ->
    val bleed = padding.roundToPx()
    val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + bleed * 2))
    layout(constraints.maxWidth, placeable.height) {
        placeable.place(-bleed, 0)
    }
}
