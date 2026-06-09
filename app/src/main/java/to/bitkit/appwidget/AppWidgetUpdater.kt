package to.bitkit.appwidget

import android.content.Context
import androidx.glance.appwidget.updateAll
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.ui.blocks.BlocksGlanceWidget
import to.bitkit.appwidget.ui.facts.FactsGlanceWidget
import to.bitkit.appwidget.ui.headlines.HeadlinesGlanceWidget
import to.bitkit.appwidget.ui.price.PriceGlanceWidget
import to.bitkit.appwidget.ui.weather.WeatherGlanceWidget
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppWidgetUpdater @Inject constructor() {
    suspend fun update(type: AppWidgetType, context: Context) {
        when (type) {
            AppWidgetType.PRICE -> PriceGlanceWidget().updateAll(context)
            AppWidgetType.HEADLINES -> HeadlinesGlanceWidget().updateAll(context)
            AppWidgetType.BLOCKS -> BlocksGlanceWidget().updateAll(context)
            AppWidgetType.FACTS -> FactsGlanceWidget().updateAll(context)
            AppWidgetType.WEATHER -> WeatherGlanceWidget().updateAll(context)
        }
    }
}
