package to.bitkit.appwidget.ui.weather

import androidx.glance.appwidget.GlanceAppWidget
import to.bitkit.appwidget.AppWidgetRefreshReason
import to.bitkit.appwidget.RefreshingGlanceReceiver

class WeatherGlanceReceiver : RefreshingGlanceReceiver(
    enabledReason = AppWidgetRefreshReason.WEATHER_WIDGET_ENABLED,
    updateReason = AppWidgetRefreshReason.WEATHER_WIDGET_UPDATE,
    disabledReason = AppWidgetRefreshReason.WEATHER_WIDGET_DISABLED,
) {
    override val glanceAppWidget: GlanceAppWidget = WeatherGlanceWidget()
}
