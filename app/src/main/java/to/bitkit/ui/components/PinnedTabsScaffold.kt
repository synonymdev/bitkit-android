package to.bitkit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import to.bitkit.ui.theme.Colors

private val PinnedTabsShadowHeight = 32.dp
private val PinnedTabsBlurRadius = 24.dp

private enum class PinnedTabsSlot { Header, Content, Shadow }

@Composable
fun PinnedTabsScaffold(
    header: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (topPadding: Dp) -> Unit,
) {
    val hazeState = rememberHazeState()
    val shadowBrush = remember {
        Brush.verticalGradient(colors = listOf(Colors.Black, Color.Transparent))
    }
    val hazeStyle = remember {
        HazeStyle(
            backgroundColor = Colors.Black,
            tint = HazeTint(Colors.Black70),
            blurRadius = PinnedTabsBlurRadius,
        )
    }

    SubcomposeLayout(modifier = modifier.fillMaxSize()) { constraints ->
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)

        val headerPlaceables = subcompose(PinnedTabsSlot.Header) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .hazeEffect(state = hazeState, style = hazeStyle),
                content = { header() },
            )
        }.map { it.measure(looseConstraints) }

        val headerHeightPx = headerPlaceables.maxOfOrNull { it.height } ?: 0
        val headerHeightDp = headerHeightPx.toDp()

        val contentPlaceables = subcompose(PinnedTabsSlot.Content) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
            ) {
                content(headerHeightDp)
            }
        }.map { it.measure(constraints) }

        val shadowPlaceables = subcompose(PinnedTabsSlot.Shadow) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PinnedTabsShadowHeight)
                    .background(shadowBrush)
            )
        }.map { it.measure(looseConstraints) }

        layout(constraints.maxWidth, constraints.maxHeight) {
            contentPlaceables.forEach { it.placeRelative(0, 0) }
            shadowPlaceables.forEach { it.placeRelative(0, headerHeightPx) }
            headerPlaceables.forEach { it.placeRelative(0, 0) }
        }
    }
}
