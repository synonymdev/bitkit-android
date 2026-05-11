package to.bitkit.appwidget.ui.price

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
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
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.PriceWidgetData
import to.bitkit.ui.theme.Colors

class PriceGlanceWidget : GlanceAppWidget() {

    companion object {
        private const val CHART_WIDTH = 600
        private const val CHART_HEIGHT = 200
    }

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
                ?: AppWidgetEntry(appWidgetId = appWidgetId, type = AppWidgetType.PRICE)
            val price = data.cachedPrices[entry.pricePreferences.period]
            val widget = remember(price, entry.pricePreferences) {
                resolveWidget(price, entry)
            }
            val chartBitmap = remember(widget) {
                widget?.let { buildChartBitmap(it) }
            }

            PriceGlanceContent(
                widget = widget,
                entry = entry,
                chartBitmap = chartBitmap,
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

    private fun resolveWidget(price: PriceDTO?, entry: AppWidgetEntry): PriceWidgetData? {
        val widgets = price?.widgets ?: return null
        val enabledPairs = entry.pricePreferences.enabledPairs
        return widgets.firstOrNull { it.pair in enabledPairs } ?: widgets.firstOrNull()
    }

    private fun buildChartBitmap(widget: PriceWidgetData): Bitmap? {
        if (widget.pastValues.size < 2) return null

        val lineColor = if (widget.change.isPositive) {
            Colors.Green.toArgb()
        } else {
            Colors.Red.toArgb()
        }

        return renderLineChartBitmap(
            values = widget.pastValues,
            width = CHART_WIDTH,
            height = CHART_HEIGHT,
            lineColor = lineColor,
        )
    }
}
