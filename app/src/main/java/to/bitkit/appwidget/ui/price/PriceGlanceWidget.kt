package to.bitkit.appwidget.ui.price

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import kotlinx.coroutines.flow.first
import to.bitkit.appwidget.AppWidgetPreferencesStore
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.ui.theme.Colors

class PriceGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(COMPACT, EXPANDED),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = AppWidgetPreferencesStore.getInstance(context)
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val data = store.data.first()
        val entry = data.entries.find { it.appWidgetId == appWidgetId }
            ?: AppWidgetEntry(appWidgetId = appWidgetId, type = AppWidgetType.PRICE)

        val chartBitmap = buildChartBitmap(data.cachedPrice, entry)

        provideContent {
            PriceGlanceContent(
                price = data.cachedPrice,
                entry = entry,
                chartBitmap = chartBitmap,
            )
        }
    }

    private fun buildChartBitmap(price: PriceDTO?, entry: AppWidgetEntry): Bitmap? {
        val prefs = entry.pricePreferences
        val enabledWidgets = price?.widgets?.filter { it.pair in prefs.enabledPairs }
        val chartData = enabledWidgets?.firstOrNull() ?: price?.widgets?.firstOrNull()
            ?: return null
        if (chartData.pastValues.size < 2) return null

        val lineColor = if (chartData.change.isPositive) {
            Colors.Green.toArgb()
        } else {
            Colors.Red.toArgb()
        }

        return renderSparklineBitmap(
            values = chartData.pastValues,
            width = CHART_WIDTH,
            height = CHART_HEIGHT,
            lineColor = lineColor,
        )
    }

    companion object {
        private const val CHART_WIDTH = 600
        private const val CHART_HEIGHT = 200
        val COMPACT = DpSize(180.dp, 80.dp)
        val EXPANDED = DpSize(180.dp, 180.dp)
    }
}
