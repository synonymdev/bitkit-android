package to.bitkit.appwidget.ui.facts

import androidx.glance.appwidget.GlanceAppWidget
import to.bitkit.appwidget.AppWidgetRefreshReason
import to.bitkit.appwidget.RefreshingGlanceReceiver

class FactsGlanceReceiver : RefreshingGlanceReceiver(
    enabledReason = AppWidgetRefreshReason.FACTS_WIDGET_ENABLED,
    updateReason = AppWidgetRefreshReason.FACTS_WIDGET_UPDATE,
    disabledReason = AppWidgetRefreshReason.FACTS_WIDGET_DISABLED,
) {
    override val glanceAppWidget: GlanceAppWidget = FactsGlanceWidget()
}
