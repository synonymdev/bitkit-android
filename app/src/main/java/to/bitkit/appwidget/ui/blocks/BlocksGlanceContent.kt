package to.bitkit.appwidget.ui.blocks

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.action.actionStartActivity
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
import to.bitkit.appwidget.ui.components.BodyM
import to.bitkit.appwidget.ui.components.BodyMSB
import to.bitkit.appwidget.ui.components.BodySSB
import to.bitkit.appwidget.ui.components.CaptionB
import to.bitkit.appwidget.ui.components.GlanceLayoutDimens
import to.bitkit.appwidget.ui.components.GlanceWidgetScaffold
import to.bitkit.appwidget.ui.components.HorizontalSpacer
import to.bitkit.appwidget.ui.components.VerticalSpacer
import to.bitkit.models.widget.BlockModel
import to.bitkit.models.widget.BlocksWidgetField
import to.bitkit.models.widget.MAX_BLOCKS_FIELDS
import to.bitkit.ui.theme.Colors

fun HomeBlocksPreferences.enabledFields(): List<BlocksWidgetField> = BlocksWidgetField.entries.filter {
    when (it) {
        BlocksWidgetField.BLOCK -> showBlock
        BlocksWidgetField.TIME -> showTime
        BlocksWidgetField.DATE -> showDate
        BlocksWidgetField.TRANSACTIONS -> showTransactions
        BlocksWidgetField.SIZE -> showSize
        BlocksWidgetField.FEES -> showFees
    }
}

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

    GlanceWidgetScaffold(onClick = actionStartActivity(configIntent)) {
        if (block == null) {
            CaptionB(text = context.getString(R.string.appwidget__loading))
            return@GlanceWidgetScaffold
        }

        val fields = entry.blocksPreferences.enabledFields().filter { it.value(block).isNotEmpty() }
        if (fields.isEmpty()) {
            CaptionB(text = context.getString(R.string.appwidget__loading))
            return@GlanceWidgetScaffold
        }

        if (LocalSize.current.width >= GlanceLayoutDimens.WIDE_LAYOUT_MIN_WIDTH) {
            WideContent(fields = fields.toImmutableList(), block = block, context = context)
        } else {
            CompactContent(fields = fields.take(MAX_BLOCKS_FIELDS).toImmutableList(), block = block)
        }
    }
}

@Suppress("RestrictedApi")
@Composable
private fun WideContent(fields: ImmutableList<BlocksWidgetField>, block: BlockModel, context: Context) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        fields.forEachIndexed { index, field ->
            if (index > 0) VerticalSpacer(8.dp)
            WideRow(
                label = context.getString(field.labelRes),
                value = field.value(block),
                icon = field.icon,
                modifier = GlanceModifier.padding(vertical = 6.dp)
            )
        }
    }
}

@Suppress("RestrictedApi")
@Composable
private fun WideRow(label: String, value: String, icon: Int, modifier: GlanceModifier = GlanceModifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ColorProvider(day = Colors.Brand, night = Colors.Brand)),
            modifier = GlanceModifier.size(20.dp)
        )
        HorizontalSpacer(8.dp)
        BodyM(
            text = label,
            color = ColorProvider(day = Colors.White80, night = Colors.White80),
            modifier = GlanceModifier.then(WidthModifier(Dimension.Expand))
        )
        BodyMSB(text = value)
    }
}

@Suppress("RestrictedApi")
@Composable
private fun CompactContent(fields: ImmutableList<BlocksWidgetField>, block: BlockModel) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        fields.forEachIndexed { index, field ->
            if (index > 0) VerticalSpacer(16.dp)
            CompactRow(value = field.value(block), icon = field.icon)
        }
    }
}

@Suppress("RestrictedApi")
@Composable
private fun CompactRow(value: String, icon: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier.fillMaxWidth()
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ColorProvider(day = Colors.Brand, night = Colors.Brand)),
            modifier = GlanceModifier.size(20.dp)
        )
        HorizontalSpacer(8.dp)
        BodySSB(text = value)
    }
}
