package to.bitkit.appwidget.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text
import to.bitkit.appwidget.ui.theme.GlanceTextStyles

@Composable
fun GlanceDataRow(
    label: String,
    value: String,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = GlanceTextStyles.captionB,
        )
        Text(
            text = value,
            style = GlanceTextStyles.bodySSB,
        )
    }
}
