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
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import to.bitkit.R
import to.bitkit.appwidget.config.AppWidgetConfigActivity
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.ui.components.CaptionB
import to.bitkit.appwidget.ui.components.GlanceLayoutDimens
import to.bitkit.appwidget.ui.components.GlanceWidgetScaffold
import to.bitkit.appwidget.ui.theme.GlanceTextStyles

private val BADGE_SIZE = 32.dp
private val BADGE_RESERVED_END = 40.dp

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

        val isWide = LocalSize.current.width >= GlanceLayoutDimens.WIDE_LAYOUT_MIN_WIDTH

        Box(
            contentAlignment = if (isWide) Alignment.TopEnd else Alignment.BottomEnd,
            modifier = GlanceModifier.fillMaxSize()
        ) {
            Text(
                text = fact,
                style = if (isWide) GlanceTextStyles.title22 else GlanceTextStyles.bodyMSB,
                maxLines = if (isWide) 3 else 5,
                modifier = if (isWide) {
                    GlanceModifier.fillMaxSize().padding(end = BADGE_RESERVED_END)
                } else {
                    GlanceModifier.fillMaxSize()
                }
            )
            BitcoinBadge()
        }
    }
}

@Composable
private fun BitcoinBadge() {
    Image(
        provider = ImageProvider(R.drawable.ic_bitcoin_badge),
        contentDescription = null,
        modifier = GlanceModifier.size(BADGE_SIZE)
    )
}
