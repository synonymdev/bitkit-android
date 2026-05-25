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
import to.bitkit.ui.theme.Colors

private val PinnedTabsShadowHeight = 32.dp

@Composable
fun PinnedTabsScaffold(
    header: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (topPadding: Dp) -> Unit,
) {
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(0.dp) }
    val shadowBrush = remember {
        Brush.verticalGradient(colors = listOf(Colors.Black, Color.Transparent))
    }

    Box(modifier = modifier.fillMaxSize()) {
        content(headerHeight)

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
                .background(Colors.Black)
                .zIndex(2f)
                .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
        ) {
            header()
        }
    }
}
