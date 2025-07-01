package to.bitkit.ui.components

import androidx.annotation.FloatRange
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

@Composable
fun VerticalSpacer(height: Dp) {
    Spacer(modifier = Modifier.height(height))
}


@Composable
fun HorizontalSpacer(width: Dp) {
    Spacer(modifier = Modifier.width(width))
}

@Composable
fun ColumnScope.FillHeight(
    @FloatRange weight: Float = 1f,
    fill: Boolean = true
) {
    Spacer(modifier = Modifier.weight(weight, fill = fill))
}

@Composable
fun RowScope.FillWidth(
    @FloatRange weight: Float = 1f,
    fill: Boolean = true
) {
    Spacer(modifier = Modifier.weight(weight, fill = fill))
}
