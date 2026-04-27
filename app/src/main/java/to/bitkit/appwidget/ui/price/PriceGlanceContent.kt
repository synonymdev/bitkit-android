package to.bitkit.appwidget.ui.price

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.cornerRadius
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.HeightModifier
import androidx.glance.layout.Row
import androidx.glance.layout.WidthModifier
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.unit.Dimension
import to.bitkit.R
import to.bitkit.appwidget.config.AppWidgetConfigActivity
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.ui.components.BodySB
import to.bitkit.appwidget.ui.components.CaptionB
import to.bitkit.appwidget.ui.components.GlanceWidgetScaffold
import to.bitkit.appwidget.ui.components.HorizontalSpacer
import to.bitkit.appwidget.ui.theme.GlanceColors
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.PriceWidgetData
import to.bitkit.ui.theme.Colors

@Suppress("RestrictedApi")
@Composable
fun PriceGlanceContent(
    price: PriceDTO?,
    entry: AppWidgetEntry,
    chartBitmap: Bitmap? = null,
) {
    val context = LocalContext.current
    val prefs = entry.pricePreferences
    val showChart = LocalSize.current.height >= 160.dp
    val configIntent = Intent(context, AppWidgetConfigActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, entry.appWidgetId)
        putExtra(AppWidgetConfigActivity.EXTRA_WIDGET_TYPE, AppWidgetType.PRICE.name)
    }

    GlanceWidgetScaffold(onClick = configIntent) {
        if (price == null) {
            CaptionB(text = context.getString(R.string.appwidget__loading))
            return@GlanceWidgetScaffold
        }

        val enabledPairs = price.widgets.filter { it.pair in prefs.enabledPairs }
        val displayWidgets = enabledPairs.ifEmpty { price.widgets.take(1) }

        displayWidgets.forEach { widget ->
            PriceRow(widget = widget)
        }

        if (showChart && chartBitmap != null) {
            val chartWidget = displayWidgets.first()
            val chartColor = if (chartWidget.change.isPositive) Colors.Green else Colors.Red
            Box(
                contentAlignment = Alignment.BottomStart,
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .then(HeightModifier(Dimension.Expand))
            ) {
                Image(
                    provider = ImageProvider(chartBitmap),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .cornerRadius(8.dp)
                )
                CaptionB(
                    text = chartWidget.period.value,
                    color = ColorProvider(day = chartColor, night = chartColor),
                    modifier = GlanceModifier.padding(7.dp)
                )
            }
        }
    }
}

@Suppress("RestrictedApi")
@Composable
private fun PriceRow(widget: PriceWidgetData) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        BodySB(
            text = widget.pair.displayName,
            color = GlanceColors.textSecondary,
            modifier = GlanceModifier.then(WidthModifier(Dimension.Expand))
        )
        BodySB(
            text = widget.change.formatted,
            color = if (widget.change.isPositive) {
                ColorProvider(day = Colors.Green, night = Colors.Green)
            } else {
                ColorProvider(day = Colors.Red, night = Colors.Red)
            },
        )
        HorizontalSpacer(16.dp)
        BodySB(text = "${widget.pair.symbol}${widget.price}")
    }
}
