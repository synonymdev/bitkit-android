package to.bitkit.appwidget.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import to.bitkit.appwidget.ui.theme.GlanceColors

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
            style = TextStyle(
                color = GlanceColors.textSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Text(
            text = value,
            style = TextStyle(
                color = GlanceColors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}
