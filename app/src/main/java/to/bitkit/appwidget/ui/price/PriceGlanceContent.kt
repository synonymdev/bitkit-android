package to.bitkit.appwidget.ui.price

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.cornerRadius
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import to.bitkit.R
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.ui.components.GlanceWidgetScaffold
import to.bitkit.appwidget.ui.components.HorizontalSpacer
import to.bitkit.appwidget.ui.theme.GlanceTextStyles
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.PriceWidgetData
import to.bitkit.ui.theme.Colors

@Composable
fun PriceGlanceContent(
    price: PriceDTO?,
    entry: AppWidgetEntry,
    chartBitmap: Bitmap? = null,
) {
    val context = LocalContext.current
    val prefs = entry.pricePreferences
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)

    GlanceWidgetScaffold(onClick = launchIntent) {
        if (price == null) {
            Text(
                text = context.getString(R.string.appwidget__loading),
                style = GlanceTextStyles.captionB,
            )
            return@GlanceWidgetScaffold
        }

        val enabledWidgets = price.widgets.filter { it.pair in prefs.enabledPairs }
        val displayWidgets = enabledWidgets.ifEmpty { price.widgets.take(1) }

        Box(modifier = GlanceModifier.fillMaxWidth().fillMaxHeight()) {
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                for (widget in displayWidgets) {
                    PriceRow(widget = widget)
                }
            }

            if (chartBitmap != null) {
                Box(
                    modifier = GlanceModifier.fillMaxWidth().fillMaxHeight(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Image(
                        provider = ImageProvider(chartBitmap),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .cornerRadius(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PriceRow(widget: PriceWidgetData) {
    Box(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = widget.pair.displayName,
            style = GlanceTextStyles.footnoteM,
        )
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
}
