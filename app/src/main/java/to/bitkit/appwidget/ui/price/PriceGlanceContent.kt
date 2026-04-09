package to.bitkit.appwidget.ui.price

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import to.bitkit.R
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.ui.components.GlanceWidgetScaffold
import to.bitkit.appwidget.ui.theme.GlanceTextStyles
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.PriceWidgetData
import to.bitkit.ui.theme.Colors

@Composable
fun PriceGlanceContent(
    context: Context,
    price: PriceDTO?,
    entry: AppWidgetEntry,
    chartBitmap: Bitmap? = null,
) {
    val prefs = entry.pricePreferences
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)

    GlanceWidgetScaffold(onClick = launchIntent) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = GlanceModifier.padding(bottom = 8.dp),
        ) {
            Image(
                provider = ImageProvider(R.drawable.widget_chart_line),
                contentDescription = null,
                modifier = GlanceModifier.size(32.dp),
            )
            Spacer(modifier = GlanceModifier.width(16.dp))
            Text(
                text = context.getString(R.string.widgets__price__name),
                style = GlanceTextStyles.bodyMSB,
            )
        }

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

        if (chartBitmap != null) {
            Spacer(modifier = GlanceModifier.height(8.dp))
            Image(
                provider = ImageProvider(chartBitmap),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxWidth().height(80.dp),
            )
        }

        if (prefs.showSource) {
            Spacer(modifier = GlanceModifier.height(8.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
            ) {
                Text(
                    text = context.getString(R.string.widgets__widget__source),
                    style = GlanceTextStyles.source,
                )
                Text(
                    text = price.source,
                    style = GlanceTextStyles.source,
                )
            }
        }
    }
}

@Composable
private fun PriceRow(widget: PriceWidgetData) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = widget.pair.displayName,
            style = GlanceTextStyles.footnoteM,
        )
        Spacer(modifier = GlanceModifier.defaultWeight())
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
        Spacer(modifier = GlanceModifier.width(16.dp))
        Text(
            text = widget.price,
            style = GlanceTextStyles.bodySSB,
        )
    }
}
