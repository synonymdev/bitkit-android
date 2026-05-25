package to.bitkit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import to.bitkit.ui.theme.Colors

private val PinnedTabsShadowHeight = 32.dp
private val PinnedTabsBlurRadius = 24.dp

@Composable
fun PinnedTabsScaffold(
    header: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (topPadding: Dp) -> Unit,
) {
    val hazeState = rememberHazeState()
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(0.dp) }
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

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
        ) {
            content(headerHeight)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = headerHeight)
                .height(PinnedTabsShadowHeight)
                .background(shadowBrush)
                .zIndex(1f)
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .zIndex(2f)
                .hazeEffect(state = hazeState, style = hazeStyle)
                .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
        ) {
            header()
        }
    }
}
