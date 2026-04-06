package to.bitkit.appwidget.ui.facts

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.layout.Spacer
import androidx.glance.layout.height
import androidx.glance.text.Text
import to.bitkit.R
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.ui.components.GlanceWidgetScaffold
import to.bitkit.appwidget.ui.theme.GlanceTextStyles

@Composable
fun FactsGlanceContent(
    context: Context,
    facts: List<String>,
    entry: AppWidgetEntry,
) {
    val prefs = entry.factsPreferences
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)

    GlanceWidgetScaffold(onClick = launchIntent) {
        Text(
            text = context.getString(R.string.widgets__facts__name),
            style = GlanceTextStyles.bodyMSB,
        )

        Spacer(modifier = GlanceModifier.height(8.dp))

        if (facts.isEmpty()) {
            Text(
                text = context.getString(R.string.appwidget__loading),
                style = GlanceTextStyles.captionB,
            )
            return@GlanceWidgetScaffold
        }

        val fact = facts.random()
        Text(
            text = fact,
            style = GlanceTextStyles.bodySSB,
            maxLines = 4,
        )

        if (prefs.showSource) {
            Spacer(modifier = GlanceModifier.height(8.dp))
            Text(
                text = context.getString(R.string.widgets__widget__source),
                style = GlanceTextStyles.source,
            )
        }
    }
}
