package to.bitkit.appwidget.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.glance.GlanceModifier
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width

@Composable
fun VerticalSpacer(height: Dp) {
    Spacer(modifier = GlanceModifier.height(height))
}

@Composable
fun HorizontalSpacer(width: Dp) {
    Spacer(modifier = GlanceModifier.width(width))
}

@Composable
fun FillWidth() {
    Spacer(modifier = GlanceModifier.fillMaxWidth())
}
