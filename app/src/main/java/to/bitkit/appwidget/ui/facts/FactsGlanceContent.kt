package to.bitkit.appwidget.ui.facts

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.layout.Alignment
import androidx.glance.layout.HeightModifier
import androidx.glance.layout.Row
import androidx.glance.layout.WidthModifier
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.unit.Dimension
import to.bitkit.R
import to.bitkit.appwidget.config.AppWidgetConfigActivity
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.ui.components.CaptionB
import to.bitkit.appwidget.ui.components.GlanceLayoutDimens
import to.bitkit.appwidget.ui.components.GlanceWidgetScaffold
import to.bitkit.appwidget.ui.theme.GlanceTextStyles

@Suppress("RestrictedApi")
@Composable
fun FactsGlanceContent(
    entry: AppWidgetEntry,
    fact: String?,
) {
    val context = LocalContext.current
    val configIntent = Intent(context, AppWidgetConfigActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, entry.appWidgetId)
        putExtra(AppWidgetConfigActivity.EXTRA_WIDGET_TYPE, AppWidgetType.FACTS.name)
    }

    GlanceWidgetScaffold(onClick = configIntent) {
        if (fact == null) {
            CaptionB(text = context.getString(R.string.appwidget__loading))
            return@GlanceWidgetScaffold
        }

        if (LocalSize.current.width >= GlanceLayoutDimens.WIDE_LAYOUT_MIN_WIDTH) {
            WideContent(fact = fact)
        } else {
            CompactContent(fact = fact)
        }
    }
}

@Suppress("RestrictedApi")
@Composable
private fun WideContent(fact: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = GlanceModifier.fillMaxWidth()
    ) {
        Text(
            text = fact,
            style = GlanceTextStyles.title22,
            maxLines = 3,
            modifier = GlanceModifier.then(WidthModifier(Dimension.Expand))
        )
        BitcoinBadge()
    }
}

@Suppress("RestrictedApi")
@Composable
private fun CompactContent(fact: String) {
    Text(
        text = fact,
        style = GlanceTextStyles.bodyMSB,
        maxLines = 5,
        modifier = GlanceModifier
            .fillMaxWidth()
            .then(HeightModifier(Dimension.Expand))
    )
    Row(
        horizontalAlignment = Alignment.End,
        modifier = GlanceModifier.fillMaxWidth()
    ) {
        BitcoinBadge()
    }
}

@Composable
private fun BitcoinBadge() {
    Image(
        provider = ImageProvider(R.drawable.ic_bitcoin_badge),
        contentDescription = null,
        modifier = GlanceModifier.size(32.dp)
    )
}
