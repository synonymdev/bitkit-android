package to.bitkit.appwidget.ui.weather

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.layout.Spacer
import androidx.glance.layout.height
import androidx.glance.text.Text
import to.bitkit.R
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.ui.components.GlanceDataRow
import to.bitkit.appwidget.ui.components.GlanceWidgetScaffold
import to.bitkit.appwidget.ui.theme.GlanceTextStyles
import to.bitkit.data.dto.FeeCondition
import to.bitkit.data.dto.WeatherDTO

@Composable
fun WeatherGlanceContent(
    context: Context,
    weather: WeatherDTO?,
    entry: AppWidgetEntry,
) {
    val prefs = entry.weatherPreferences
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)

    GlanceWidgetScaffold(onClick = launchIntent) {
        Text(
            text = context.getString(R.string.widgets__weather__name),
            style = GlanceTextStyles.bodyMSB,
        )

        Spacer(modifier = GlanceModifier.height(8.dp))

        if (weather == null) {
            Text(
                text = context.getString(R.string.appwidget__loading),
                style = GlanceTextStyles.captionB,
            )
            return@GlanceWidgetScaffold
        }

        if (prefs.showTitle) {
            Text(
                text = "${weather.condition.icon} ${conditionLabel(context, weather.condition)}",
                style = GlanceTextStyles.subtitle,
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
        }

        if (prefs.showDescription) {
            Text(
                text = conditionDescription(context, weather.condition),
                style = GlanceTextStyles.footnoteM,
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
        }

        if (prefs.showCurrentFee) {
            GlanceDataRow(
                label = context.getString(R.string.widgets__weather__current_fee),
                value = weather.currentFee,
            )
        }

        if (prefs.showNextBlockFee) {
            GlanceDataRow(
                label = context.getString(R.string.widgets__weather__next_block),
                value = "${weather.nextBlockFee} sat/vB",
            )
        }
    }
}

private fun conditionLabel(context: Context, condition: FeeCondition): String = when (condition) {
    FeeCondition.GOOD -> context.getString(R.string.widgets__weather__condition__good__title)
    FeeCondition.AVERAGE -> context.getString(R.string.widgets__weather__condition__average__title)
    FeeCondition.POOR -> context.getString(R.string.widgets__weather__condition__poor__title)
}

private fun conditionDescription(context: Context, condition: FeeCondition): String =
    when (condition) {
        FeeCondition.GOOD -> context.getString(R.string.widgets__weather__condition__good__description)
        FeeCondition.AVERAGE -> context.getString(R.string.widgets__weather__condition__average__description)
        FeeCondition.POOR -> context.getString(R.string.widgets__weather__condition__poor__description)
    }
