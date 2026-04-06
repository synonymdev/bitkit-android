package to.bitkit.appwidget.ui.theme

import androidx.glance.color.ColorProvider
import to.bitkit.ui.theme.Colors

object GlanceColors {
    val cardBackgroundProvider = ColorProvider(day = Colors.Gray5, night = Colors.Gray5)
    val textPrimary = ColorProvider(day = Colors.White, night = Colors.White)
    val textSecondary = ColorProvider(day = Colors.White64, night = Colors.White64)
    val textTertiary = ColorProvider(day = Colors.White50, night = Colors.White50)
}
