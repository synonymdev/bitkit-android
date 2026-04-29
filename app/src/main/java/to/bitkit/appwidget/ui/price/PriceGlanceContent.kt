package to.bitkit.appwidget.ui.price

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.annotation.StringRes
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
import to.bitkit.appwidget.config.AppWidgetConfigActivity
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.ui.components.CaptionB
import to.bitkit.appwidget.ui.components.GlanceLayoutDimens
import to.bitkit.appwidget.ui.components.GlanceWidgetScaffold
import to.bitkit.appwidget.ui.components.HorizontalSpacer
import to.bitkit.appwidget.ui.components.VerticalSpacer
import to.bitkit.appwidget.ui.theme.GlanceTextStyles
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.PriceWidgetData
import to.bitkit.ui.theme.Colors

@Suppress("RestrictedApi")
@Composable
fun PriceGlanceContent(
    widget: PriceWidgetData?,
    priceAvailable: Boolean,
    entry: AppWidgetEntry,
    chartBitmap: Bitmap? = null,
) {
    val context = LocalContext.current
    val configIntent = Intent(context, AppWidgetConfigActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, entry.appWidgetId)
        putExtra(AppWidgetConfigActivity.EXTRA_WIDGET_TYPE, AppWidgetType.PRICE.name)
    }

    GlanceWidgetScaffold(onClick = configIntent) {
        if (!priceAvailable || widget == null) {
            CaptionB(text = context.getString(R.string.appwidget__loading))
            return@GlanceWidgetScaffold
        }

        if (LocalSize.current.width >= GlanceLayoutDimens.WIDE_LAYOUT_MIN_WIDTH) {
            WideContent(widget = widget, chartBitmap = chartBitmap)
        } else {
            CompactContent(widget = widget, chartBitmap = chartBitmap)
        }
    }
}

@Suppress("RestrictedApi")
@Composable
private fun WideContent(widget: PriceWidgetData, chartBitmap: Bitmap?) {
    val changeColor = if (widget.change.isPositive) Colors.Green else Colors.Red
    val periodLabel = LocalContext.current.getString(widget.period.labelRes())

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier.fillMaxWidth()
    ) {
        Text(
            text = "${widget.pair.displayName}  $periodLabel".uppercase(),
            style = GlanceTextStyles.captionUp,
            modifier = GlanceModifier.then(WidthModifier(Dimension.Expand))
        )
        HorizontalSpacer(16.dp)
        Text(
            text = widget.change.formatted,
            style = GlanceTextStyles.headlineChange22.copy(
                color = ColorProvider(day = changeColor, night = changeColor),
            ),
        )
    }
    VerticalSpacer(4.dp)
    Text(
        text = "${widget.pair.symbol} ${widget.price}",
        style = GlanceTextStyles.headline34,
        modifier = GlanceModifier.fillMaxWidth()
    )
    VerticalSpacer(8.dp)
    ChartBox(chartBitmap = chartBitmap)
}

@Suppress("RestrictedApi")
@Composable
private fun CompactContent(widget: PriceWidgetData, chartBitmap: Bitmap?) {
    val changeColor = if (widget.change.isPositive) Colors.Green else Colors.Red
    val periodLabel = LocalContext.current.getString(widget.period.labelRes())

    Row(modifier = GlanceModifier.fillMaxWidth()) {
        Text(
            text = widget.pair.displayName.uppercase(),
            style = GlanceTextStyles.captionUp,
            modifier = GlanceModifier.then(WidthModifier(Dimension.Expand))
        )
        Text(
            text = periodLabel.uppercase(),
            style = GlanceTextStyles.captionUp,
        )
    }
    VerticalSpacer(8.dp)
    Text(
        text = "${widget.pair.symbol} ${widget.price}",
        style = GlanceTextStyles.title22,
        modifier = GlanceModifier.fillMaxWidth()
    )
    VerticalSpacer(8.dp)
    Text(
        text = widget.change.formatted,
        style = GlanceTextStyles.bodySSB.copy(
            color = ColorProvider(day = changeColor, night = changeColor),
        ),
    )
    ChartBox(chartBitmap = chartBitmap)
}

@Suppress("RestrictedApi")
@Composable
private fun ChartBox(chartBitmap: Bitmap?) {
    if (chartBitmap == null) return
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .then(HeightModifier(Dimension.Expand))
            .padding(vertical = 16.dp)
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
    }
}

@StringRes
private fun GraphPeriod.labelRes(): Int = when (this) {
    GraphPeriod.ONE_DAY -> R.string.appwidget__price__day
    GraphPeriod.ONE_WEEK -> R.string.appwidget__price__week
    GraphPeriod.ONE_MONTH -> R.string.appwidget__price__month
    GraphPeriod.ONE_YEAR -> R.string.appwidget__price__year
}
