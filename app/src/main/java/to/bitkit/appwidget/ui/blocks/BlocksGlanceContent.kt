package to.bitkit.appwidget.ui.blocks

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.ui.components.GlanceDataRow
import to.bitkit.appwidget.ui.components.GlanceWidgetScaffold
import to.bitkit.appwidget.ui.theme.GlanceTextStyles
import to.bitkit.models.widget.BlockModel

@Composable
fun BlocksGlanceContent(
    context: Context,
    block: BlockModel?,
    entry: AppWidgetEntry,
) {
    val prefs = entry.blocksPreferences
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)

    GlanceWidgetScaffold(onClick = launchIntent) {
        Text(
            text = context.getString(to.bitkit.R.string.widgets__blocks__name),
            style = GlanceTextStyles.bodyMSB,
        )

        Spacer(modifier = GlanceModifier.height(8.dp))

        if (block == null) {
            Text(
                text = context.getString(to.bitkit.R.string.appwidget__loading),
                style = GlanceTextStyles.captionB,
            )
            return@GlanceWidgetScaffold
        }

        if (prefs.showBlock && block.height.isNotEmpty()) {
            GlanceDataRow(label = "Block", value = block.height)
        }
        if (prefs.showTime && block.time.isNotEmpty()) {
            GlanceDataRow(label = "Time", value = block.time)
        }
        if (prefs.showDate && block.date.isNotEmpty()) {
            GlanceDataRow(label = "Date", value = block.date)
        }
        if (prefs.showTransactions && block.transactionCount.isNotEmpty()) {
            GlanceDataRow(label = "Txs", value = block.transactionCount)
        }
        if (prefs.showSize && block.size.isNotEmpty()) {
            GlanceDataRow(label = "Size", value = block.size)
        }
        if (prefs.showSource && block.source.isNotEmpty()) {
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = block.source,
                style = GlanceTextStyles.source,
                modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}
