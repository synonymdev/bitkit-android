package to.bitkit.appwidget.ui.theme

import androidx.compose.ui.unit.sp
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle

object GlanceTextStyles {
    val subtitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = GlanceColors.textPrimary)
    val bodyMSB = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = GlanceColors.textPrimary)
    val bodySSB = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = GlanceColors.textPrimary)
    val captionB = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = GlanceColors.textSecondary)
    val footnoteM = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = GlanceColors.textSecondary)
}
