package to.bitkit.appwidget.ui.headlines

import androidx.glance.appwidget.GlanceAppWidget
import to.bitkit.appwidget.AppWidgetRefreshReason
import to.bitkit.appwidget.RefreshingGlanceReceiver

class HeadlinesGlanceReceiver : RefreshingGlanceReceiver(
    enabledReason = AppWidgetRefreshReason.HEADLINES_WIDGET_ENABLED,
    updateReason = AppWidgetRefreshReason.HEADLINES_WIDGET_UPDATE,
    disabledReason = AppWidgetRefreshReason.HEADLINES_WIDGET_DISABLED,
) {
    override val glanceAppWidget: GlanceAppWidget = HeadlinesGlanceWidget()
}
