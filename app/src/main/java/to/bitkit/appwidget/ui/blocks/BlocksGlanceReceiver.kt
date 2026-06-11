package to.bitkit.appwidget.ui.blocks

import androidx.glance.appwidget.GlanceAppWidget
import to.bitkit.appwidget.AppWidgetRefreshReason
import to.bitkit.appwidget.RefreshingGlanceReceiver

class BlocksGlanceReceiver : RefreshingGlanceReceiver(
    enabledReason = AppWidgetRefreshReason.BLOCKS_WIDGET_ENABLED,
    updateReason = AppWidgetRefreshReason.BLOCKS_WIDGET_UPDATE,
    disabledReason = AppWidgetRefreshReason.BLOCKS_WIDGET_DISABLED,
) {
    override val glanceAppWidget: GlanceAppWidget = BlocksGlanceWidget()
}
