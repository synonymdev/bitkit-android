package to.bitkit.ui.components

import androidx.annotation.FloatRange
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun VerticalSpacer(height: Dp) {
    Spacer(modifier = Modifier.height(height))
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
        modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TopBarSpacer(
    modifier: Modifier = Modifier
) {
    Spacer(
        modifier.height(TopAppBarDefaults.MediumAppBarCollapsedHeight)
    )
}
