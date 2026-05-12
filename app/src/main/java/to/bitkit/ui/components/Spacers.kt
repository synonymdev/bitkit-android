package to.bitkit.ui.components

import androidx.annotation.FloatRange
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.bitkit.ui.theme.Insets
import to.bitkit.ui.theme.TopBarHeight

@Composable
fun VerticalSpacer(height: Dp) {
    Spacer(modifier = Modifier.height(height))
}

@Composable
fun ColumnScope.VerticalSpacer(minHeight: Dp, maxHeight: Dp) {
    Spacer(
        modifier = Modifier
            .weight(1f)
            .sizeIn(minHeight = minHeight, maxHeight = maxHeight)
    )
}

@Composable
fun HorizontalSpacer(width: Dp) {
    Spacer(modifier = Modifier.width(width))
}

@Suppress("ComposeMultipleContentEmitters")
@Composable
fun ColumnScope.FillHeight(
    @FloatRange weight: Float = 1f,
    fill: Boolean = true,
    min: Dp = 0.dp,
) {
    if (min > 0.dp) Spacer(modifier = Modifier.height(min))
    Spacer(modifier = Modifier.weight(weight, fill = fill))
}

@Suppress("ComposeMultipleContentEmitters")
@Composable
fun RowScope.FillWidth(
    @FloatRange weight: Float = 1f,
    fill: Boolean = true,
    min: Dp = 0.dp,
) {
    if (min > 0.dp) Spacer(modifier = Modifier.width(min))
    Spacer(modifier = Modifier.weight(weight, fill = fill))
}

@Composable
fun StatusBarSpacer(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier.height(Insets.Top)
    )
}

@Composable
fun TopBarSpacer(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier.height(TopBarHeight)
    )
}

@Composable
fun NavBarSpacer(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier.height(Insets.Bottom)
    )
}
