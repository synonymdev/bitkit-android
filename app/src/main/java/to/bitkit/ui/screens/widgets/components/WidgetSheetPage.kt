package to.bitkit.ui.screens.widgets.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import to.bitkit.ui.components.Subtitle
import to.bitkit.ui.theme.Colors

private val WidgetSheetTitleTopPadding = 32.dp
private val WidgetSheetTitleHeight = 32.dp
private val WidgetSheetTopBarTitleInset = 16.dp

val WidgetSheetTopPadding = WidgetSheetTitleTopPadding - WidgetSheetTopBarTitleInset

fun Modifier.widgetSheetPage(): Modifier = this
    .padding(top = WidgetSheetTopPadding)

fun Modifier.widgetSheetContent(): Modifier = this
    .fillMaxSize()
    .widgetSheetSurface()

fun Modifier.widgetSheetSurface(): Modifier = this
    .background(Colors.Gray7)

@Composable
fun WidgetSheetTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = WidgetSheetTitleTopPadding)
            .height(WidgetSheetTitleHeight)
    ) {
        Subtitle(
            text = title,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
