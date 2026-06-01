package to.bitkit.appwidget.ui.weather

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.HeightModifier
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.WidthModifier
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.Dimension
import to.bitkit.R
import to.bitkit.appwidget.config.AppWidgetConfigActivity
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.model.HomeWeatherPreferences
import to.bitkit.appwidget.ui.components.BodySSB
import to.bitkit.appwidget.ui.components.CaptionB
import to.bitkit.appwidget.ui.components.GlanceLayoutDimens
import to.bitkit.appwidget.ui.components.GlanceWidgetScaffold
import to.bitkit.appwidget.ui.components.Subtitle
import to.bitkit.appwidget.ui.components.VerticalSpacer
import to.bitkit.appwidget.ui.theme.GlanceColors
import to.bitkit.data.dto.FeeCondition
import to.bitkit.models.widget.WeatherDataOption
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.ui.screens.widgets.blocks.WeatherModel
import to.bitkit.ui.theme.Colors
import androidx.glance.unit.ColorProvider as UnitColorProvider

private val LARGE_EMOJI_SIZE = 72.sp
private val SMALL_EMOJI_SIZE = 60.sp
private val FEE_VALUE_SIZE = 28.sp

@Suppress("RestrictedApi")
@Composable
fun WeatherGlanceContent(
    entry: AppWidgetEntry,
    weather: WeatherModel?,
) {
    val context = LocalContext.current
    val configIntent = Intent(context, AppWidgetConfigActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, entry.appWidgetId)
        putExtra(AppWidgetConfigActivity.EXTRA_WIDGET_TYPE, AppWidgetType.WEATHER.name)
    }

    GlanceWidgetScaffold(onClick = actionStartActivity(configIntent)) {
        if (weather == null) {
            CaptionB(text = context.getString(R.string.appwidget__loading))
            return@GlanceWidgetScaffold
        }

        if (LocalSize.current.width >= GlanceLayoutDimens.WIDE_LAYOUT_MIN_WIDTH) {
            WideContent(weather = weather, preferences = entry.weatherPreferences.toPreferences())
        } else {
            CompactContent(weather = weather, preferences = entry.weatherPreferences.toPreferences())
        }
    }
}

@Suppress("RestrictedApi")
@Composable
private fun WideContent(weather: WeatherModel, preferences: WeatherPreferences) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.Top,
        modifier = GlanceModifier.fillMaxSize()
    ) {
        Column(
            modifier = GlanceModifier
                .then(WidthModifier(Dimension.Expand))
                .fillMaxHeight()
        ) {
            Subtitle(
                text = context.getString(weather.title),
                maxLines = 1,
            )
            VerticalSpacer(4.dp)
            BodySSB(
                text = context.getString(weather.description),
                color = GlanceColors.textSecondary,
                maxLines = 2,
            )
            Spacer(modifier = GlanceModifier.then(HeightModifier(Dimension.Expand)))
            FeeBlock(weather = weather, preferences = preferences)
        }
        WeatherEmoji(icon = weather.icon, fontSize = LARGE_EMOJI_SIZE)
    }
}

@Suppress("RestrictedApi")
@Composable
private fun CompactContent(weather: WeatherModel, preferences: WeatherPreferences) {
    val context = LocalContext.current
    Column(modifier = GlanceModifier.fillMaxSize()) {
        WeatherEmoji(icon = weather.icon, fontSize = SMALL_EMOJI_SIZE)
        VerticalSpacer(4.dp)
        Subtitle(
            text = context.getString(weather.shortTitle),
            maxLines = 1,
        )
        Spacer(modifier = GlanceModifier.then(HeightModifier(Dimension.Expand)))
        FeeBlock(weather = weather, preferences = preferences)
    }
}

@Suppress("RestrictedApi")
@Composable
private fun FeeBlock(weather: WeatherModel, preferences: WeatherPreferences) {
    val selected = preferences.selectedOption ?: return
    val context = LocalContext.current
    val (labelRes, value) = when (selected) {
        WeatherDataOption.CURRENT_FEE_FIAT ->
            R.string.widgets__weather__current_fee to weather.currentFee
        WeatherDataOption.CURRENT_FEE_SATS ->
            R.string.widgets__weather__current_fee to weather.currentFeeSatsFormatted
        WeatherDataOption.NEXT_BLOCK_INCLUSION ->
            R.string.widgets__weather__next_block to weather.nextBlockFee
    }

    Column(modifier = GlanceModifier.fillMaxWidth()) {
        CaptionB(
            text = context.getString(labelRes).uppercase(),
            color = GlanceColors.textSecondary,
            maxLines = 1,
        )
        VerticalSpacer(2.dp)
        FeeValueText(text = value, color = weather.condition.feeColorProvider())
    }
}

@Composable
private fun FeeValueText(text: String, color: UnitColorProvider) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = FEE_VALUE_SIZE,
            fontWeight = FontWeight.Bold,
            color = color,
        ),
        maxLines = 1,
    )
}

@Composable
private fun WeatherEmoji(icon: String, fontSize: TextUnit) {
    Text(
        text = icon,
        style = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            color = GlanceColors.textPrimary,
        ),
    )
}

private fun FeeCondition.feeColorProvider() = when (this) {
    FeeCondition.GOOD -> ColorProvider(day = Colors.Green, night = Colors.Green)
    FeeCondition.AVERAGE -> ColorProvider(day = Colors.Yellow, night = Colors.Yellow)
    FeeCondition.POOR -> ColorProvider(day = Colors.Red, night = Colors.Red)
}

private fun HomeWeatherPreferences.toPreferences(): WeatherPreferences =
    WeatherPreferences(selectedOption = selectedOption)
