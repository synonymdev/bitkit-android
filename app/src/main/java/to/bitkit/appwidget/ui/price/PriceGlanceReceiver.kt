package to.bitkit.appwidget.ui.price

import androidx.glance.appwidget.GlanceAppWidget
import to.bitkit.appwidget.AppWidgetRefreshReason
import to.bitkit.appwidget.RefreshingGlanceReceiver

class PriceGlanceReceiver : RefreshingGlanceReceiver(
    enabledReason = AppWidgetRefreshReason.PRICE_WIDGET_ENABLED,
    updateReason = AppWidgetRefreshReason.PRICE_WIDGET_UPDATE,
    disabledReason = AppWidgetRefreshReason.PRICE_WIDGET_DISABLED,
) {
    override val glanceAppWidget: GlanceAppWidget = PriceGlanceWidget()
}
