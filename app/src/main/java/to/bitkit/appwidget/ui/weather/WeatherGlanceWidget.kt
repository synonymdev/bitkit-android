package to.bitkit.appwidget.ui.weather

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import dagger.hilt.android.EntryPointAccessors
import to.bitkit.appwidget.AppWidgetEntryPoint
import to.bitkit.appwidget.model.AppWidgetData
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.ui.components.GlanceLayoutDimens
import to.bitkit.ui.screens.widgets.blocks.toWeatherModel

class WeatherGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(GlanceLayoutDimens.COMPACT_WIDGET_SIZE, GlanceLayoutDimens.WIDE_WIDGET_SIZE),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = EntryPointAccessors
            .fromApplication(context, AppWidgetEntryPoint::class.java)
            .appWidgetPreferencesStore()
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)

        provideContent {
            val data by store.data.collectAsState(initial = AppWidgetData())
            val entry = data.entries.find { it.appWidgetId == appWidgetId }
                ?: AppWidgetEntry(appWidgetId = appWidgetId, type = AppWidgetType.WEATHER)
            val weather = data.cachedWeather?.toWeatherModel()

            WeatherGlanceContent(
                entry = entry,
                weather = weather,
            )
        }
    }

    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        EntryPointAccessors
            .fromApplication(context, AppWidgetEntryPoint::class.java)
            .appWidgetPreferencesStore()
            .unregisterWidget(appWidgetId)
    }
}
