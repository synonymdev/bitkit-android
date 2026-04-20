package to.bitkit.appwidget.ui.price

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
import androidx.glance.text.Text
import androidx.glance.unit.Dimension
import to.bitkit.R
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.ui.components.GlanceWidgetScaffold
import to.bitkit.appwidget.ui.components.HorizontalSpacer
import to.bitkit.appwidget.ui.theme.GlanceTextStyles
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

    GlanceWidgetScaffold {
        if (price == null) {
            Text(
                text = context.getString(R.string.appwidget__loading),
                style = GlanceTextStyles.captionB,
            )
            return@GlanceWidgetScaffold
        }

        val enabledWidgets = price.widgets.filter { it.pair in prefs.enabledPairs }
        val displayWidgets = enabledWidgets.ifEmpty { price.widgets.take(1) }

        for (widget in displayWidgets) {
            PriceRow(widget = widget)
        }

        if (showChart && chartBitmap != null) {
            val chartWidget = displayWidgets.first()
            val chartColor = if (chartWidget.change.isPositive) Colors.Green else Colors.Red
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .then(HeightModifier(Dimension.Expand)),
                contentAlignment = Alignment.BottomStart,
            ) {
                Image(
                    provider = ImageProvider(chartBitmap),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .cornerRadius(8.dp),
                )
                Text(
                    text = chartWidget.period.value,
                    style = GlanceTextStyles.captionB.copy(
                        color = ColorProvider(day = chartColor, night = chartColor),
                    ),
                    modifier = GlanceModifier.padding(7.dp),
                )
            }
        }
    }
}

@Suppress("RestrictedApi")
@Composable
private fun PriceRow(widget: PriceWidgetData) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = widget.pair.displayName,
            style = GlanceTextStyles.footnoteM,
            modifier = GlanceModifier.then(WidthModifier(Dimension.Expand)),
        )
        Text(
            text = widget.change.formatted,
            style = GlanceTextStyles.captionB.copy(
                color = if (widget.change.isPositive) {
                    ColorProvider(day = Colors.Green, night = Colors.Green)
                } else {
                    ColorProvider(day = Colors.Red, night = Colors.Red)
                },
            ),
        )
        HorizontalSpacer(16.dp)
        Text(
            text = "${widget.pair.symbol}${widget.price}",
            style = GlanceTextStyles.bodySSB,
        )
    }
}
