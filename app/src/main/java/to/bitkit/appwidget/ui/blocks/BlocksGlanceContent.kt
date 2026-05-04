package to.bitkit.appwidget.ui.blocks

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.WidthModifier
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.unit.Dimension
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import to.bitkit.R
import to.bitkit.appwidget.config.AppWidgetConfigActivity
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.model.HomeBlocksPreferences
import to.bitkit.appwidget.ui.components.BodyMSB
import to.bitkit.appwidget.ui.components.BodySSB
import to.bitkit.appwidget.ui.components.CaptionB
import to.bitkit.appwidget.ui.components.GlanceLayoutDimens
import to.bitkit.appwidget.ui.components.GlanceWidgetScaffold
import to.bitkit.appwidget.ui.components.HorizontalSpacer
import to.bitkit.appwidget.ui.components.VerticalSpacer
import to.bitkit.appwidget.ui.theme.GlanceColors
import to.bitkit.models.widget.BlockModel
import to.bitkit.ui.theme.Colors

private const val MAX_SMALL_ROWS = 4

private data class BlockRow(
    @DrawableRes val icon: Int,
    val label: String,
    val value: String,
)

@Suppress("RestrictedApi")
@Composable
fun BlocksGlanceContent(
    entry: AppWidgetEntry,
    block: BlockModel?,
) {
    val context = LocalContext.current
    val configIntent = Intent(context, AppWidgetConfigActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, entry.appWidgetId)
        putExtra(AppWidgetConfigActivity.EXTRA_WIDGET_TYPE, AppWidgetType.BLOCKS.name)
    }

    GlanceWidgetScaffold(onClick = configIntent) {
        if (block == null) {
            CaptionB(text = context.getString(R.string.appwidget__loading))
            return@GlanceWidgetScaffold
        }

        val rows = buildRows(context, entry.blocksPreferences, block)
        if (rows.isEmpty()) {
            CaptionB(text = context.getString(R.string.appwidget__loading))
            return@GlanceWidgetScaffold
        }

        if (LocalSize.current.width >= GlanceLayoutDimens.WIDE_LAYOUT_MIN_WIDTH) {
            WideContent(rows = rows)
        } else {
            CompactContent(rows = rows.take(MAX_SMALL_ROWS).toImmutableList())
        }
    }
}

@Suppress("RestrictedApi")
@Composable
private fun WideContent(rows: ImmutableList<BlockRow>) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        rows.forEach { row ->
            WideRow(row = row, modifier = GlanceModifier.padding(vertical = 6.dp))
        }
    }
}

@Suppress("RestrictedApi")
@Composable
private fun WideRow(row: BlockRow, modifier: GlanceModifier = GlanceModifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        Image(
            provider = ImageProvider(row.icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ColorProvider(day = Colors.Brand, night = Colors.Brand)),
            modifier = GlanceModifier.size(20.dp)
        )
        HorizontalSpacer(8.dp)
        BodyMSB(
            text = row.label,
            color = GlanceColors.textSecondary,
            modifier = GlanceModifier.then(WidthModifier(Dimension.Expand))
        )
        BodyMSB(text = row.value)
    }
}

@Suppress("RestrictedApi")
@Composable
private fun CompactContent(rows: ImmutableList<BlockRow>) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        rows.forEachIndexed { index, row ->
            if (index > 0) VerticalSpacer(16.dp)
            CompactRow(row = row)
        }
    }
}

@Suppress("RestrictedApi")
@Composable
private fun CompactRow(row: BlockRow) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier.fillMaxWidth()
    ) {
        Image(
            provider = ImageProvider(row.icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ColorProvider(day = Colors.Brand, night = Colors.Brand)),
            modifier = GlanceModifier.size(20.dp)
        )
        HorizontalSpacer(8.dp)
        BodySSB(text = row.value)
    }
}

private fun buildRows(
    context: android.content.Context,
    preferences: HomeBlocksPreferences,
    block: BlockModel,
): ImmutableList<BlockRow> = listOfNotNull(
    BlockRow(
        icon = R.drawable.ic_cube,
        label = context.getString(R.string.widgets__blocks__field__block),
        value = block.height,
    ).takeIf { preferences.showBlock && block.height.isNotEmpty() },
    BlockRow(
        icon = R.drawable.ic_clock,
        label = context.getString(R.string.widgets__blocks__field__time),
        value = block.time,
    ).takeIf { preferences.showTime && block.time.isNotEmpty() },
    BlockRow(
        icon = R.drawable.ic_calendar,
        label = context.getString(R.string.widgets__blocks__field__date),
        value = block.date,
    ).takeIf { preferences.showDate && block.date.isNotEmpty() },
    BlockRow(
        icon = R.drawable.ic_transfer,
        label = context.getString(R.string.widgets__blocks__field__transactions),
        value = block.transactionCount,
    ).takeIf { preferences.showTransactions && block.transactionCount.isNotEmpty() },
    BlockRow(
        icon = R.drawable.ic_file_text,
        label = context.getString(R.string.widgets__blocks__field__size),
        value = block.size,
    ).takeIf { preferences.showSize && block.size.isNotEmpty() },
    BlockRow(
        icon = R.drawable.ic_coins,
        label = context.getString(R.string.widgets__blocks__field__fees),
        value = block.fees,
    ).takeIf { preferences.showFees && block.fees.isNotEmpty() },
    BlockRow(
        icon = R.drawable.ic_globe,
        label = context.getString(R.string.widgets__widget__source),
        value = block.source,
    ).takeIf { preferences.showSource && block.source.isNotEmpty() },
).toImmutableList()
