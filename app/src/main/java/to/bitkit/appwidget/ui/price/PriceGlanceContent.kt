package to.bitkit.appwidget.ui.price

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
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
) {
    val prefs = entry.pricePreferences
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)

    GlanceWidgetScaffold(onClick = launchIntent) {
        Text(
            text = context.getString(R.string.widgets__price__name),
            style = GlanceTextStyles.bodyMSB,
        )

        Spacer(modifier = GlanceModifier.height(8.dp))

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
            Spacer(modifier = GlanceModifier.height(4.dp))
        }

        if (prefs.showSource) {
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = price.source,
                style = GlanceTextStyles.source,
            )
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
            text = "${widget.pair.symbol}${widget.price}",
            style = GlanceTextStyles.subtitle,
        )
        Spacer(modifier = GlanceModifier.width(8.dp))
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
    }
    Text(
        text = widget.pair.displayName,
        style = GlanceTextStyles.footnoteM,
    )
}
