package to.bitkit.appwidget.ui.weather

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import to.bitkit.data.dto.WeatherDTO
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.ui.screens.widgets.blocks.toWeatherModel

class WeatherGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(GlanceLayoutDimens.COMPACT_WIDGET_SIZE, GlanceLayoutDimens.WIDE_WIDGET_SIZE),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val accessor = EntryPointAccessors
            .fromApplication(context, AppWidgetEntryPoint::class.java)
        val store = accessor.appWidgetPreferencesStore()
        val currencyRepo = accessor.currencyRepo()
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)

        provideContent {
            val data by store.data.collectAsState(initial = AppWidgetData())
            val currencyState by currencyRepo.currencyState.collectAsState()
            val entry = data.entries.find { it.appWidgetId == appWidgetId }
                ?: AppWidgetEntry(appWidgetId = appWidgetId, type = AppWidgetType.WEATHER)
            val weather = remember(data.cachedWeather, currencyState) {
                data.cachedWeather?.toGlanceWeather(currencyRepo)
            }

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

    private fun WeatherDTO.toGlanceWeather(currencyRepo: CurrencyRepo) = toWeatherModel(
        currentFee = currencyRepo.formatSatsAsFiatWithSymbol(avgFeeSats, withSpace = true) ?: currentFee,
    )
}
