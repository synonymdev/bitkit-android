package to.bitkit.appwidget.ui.blocks

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import dagger.hilt.android.EntryPointAccessors
import to.bitkit.appwidget.AppWidgetEntryPoint
import to.bitkit.appwidget.model.AppWidgetData
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.models.widget.toBlockModel

class BlocksGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = EntryPointAccessors
            .fromApplication(context, AppWidgetEntryPoint::class.java)
            .appWidgetPreferencesStore()
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)

        provideContent {
            val data by store.data.collectAsState(initial = AppWidgetData())
            val entry = data.entries.find { it.appWidgetId == appWidgetId }
                ?: AppWidgetEntry(appWidgetId = appWidgetId, type = AppWidgetType.BLOCKS)
            val block = data.cachedBlock?.toBlockModel()

            BlocksGlanceContent(
                entry = entry,
                block = block,
            )
        }
    }
}
