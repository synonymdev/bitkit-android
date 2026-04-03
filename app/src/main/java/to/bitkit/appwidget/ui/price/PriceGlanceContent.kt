package to.bitkit.appwidget.ui.price

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import to.bitkit.R
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.ui.components.GlanceWidgetScaffold
import to.bitkit.appwidget.ui.theme.GlanceColors
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.PriceWidgetData

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
            style = TextStyle(
                color = GlanceColors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            ),
        )

        Spacer(modifier = GlanceModifier.height(8.dp))

        if (price == null) {
            Text(
                text = context.getString(R.string.appwidget__loading),
                style = TextStyle(
                    color = GlanceColors.textSecondary,
                    fontSize = 13.sp,
                ),
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
                style = TextStyle(
                    color = GlanceColors.textTertiary,
                    fontSize = 11.sp,
                ),
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
            style = TextStyle(
                color = GlanceColors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(modifier = GlanceModifier.width(8.dp))
        Text(
            text = widget.change.formatted,
            style = TextStyle(
                color = if (widget.change.isPositive) {
                    androidx.glance.color.ColorProvider(
                        day = GlanceColors.Green,
                        night = GlanceColors.Green,
                    )
                } else {
                    androidx.glance.color.ColorProvider(
                        day = GlanceColors.Red,
                        night = GlanceColors.Red,
                    )
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
    Text(
        text = widget.pair.displayName,
        style = TextStyle(
            color = GlanceColors.textSecondary,
            fontSize = 12.sp,
        ),
    )
}
