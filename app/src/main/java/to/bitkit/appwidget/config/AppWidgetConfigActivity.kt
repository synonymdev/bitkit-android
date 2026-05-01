package to.bitkit.appwidget.config

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.AndroidEntryPoint
import to.bitkit.appwidget.AppWidgetRefreshWorker
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.ui.blocks.BlocksGlanceReceiver
import to.bitkit.appwidget.ui.blocks.BlocksGlanceWidget
import to.bitkit.appwidget.ui.headlines.HeadlinesGlanceReceiver
import to.bitkit.appwidget.ui.headlines.HeadlinesGlanceWidget
import to.bitkit.appwidget.ui.price.PriceGlanceReceiver
import to.bitkit.appwidget.ui.price.PriceGlanceWidget
import to.bitkit.ui.theme.AppThemeSurface

@AndroidEntryPoint
class AppWidgetConfigActivity : ComponentActivity() {

    companion object {
        const val EXTRA_WIDGET_TYPE = "extra_widget_type"
    }

    private val viewModel: AppWidgetConfigViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        setResult(RESULT_CANCELED)

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val type = resolveWidgetType(appWidgetId)

        viewModel.init(appWidgetId, type)

        setContent {
            AppThemeSurface {
                AppWidgetConfigScreen(
                    viewModel = viewModel,
                    onConfirm = {
                        when (viewModel.uiState.value.type) {
                            AppWidgetType.PRICE -> PriceGlanceWidget().updateAll(this@AppWidgetConfigActivity)
                            AppWidgetType.HEADLINES -> HeadlinesGlanceWidget().updateAll(this@AppWidgetConfigActivity)
                            AppWidgetType.BLOCKS -> BlocksGlanceWidget().updateAll(this@AppWidgetConfigActivity)
                            AppWidgetType.FACTS -> Unit
                        }
                        AppWidgetRefreshWorker.enqueue(this@AppWidgetConfigActivity)
                        val result = Intent().putExtra(
                            AppWidgetManager.EXTRA_APPWIDGET_ID,
                            appWidgetId,
                        )
                        setResult(Activity.RESULT_OK, result)
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }

    private fun resolveWidgetType(appWidgetId: Int): AppWidgetType {
        val extraType = intent?.getStringExtra(EXTRA_WIDGET_TYPE)
            ?.let { runCatching { AppWidgetType.valueOf(it) }.getOrNull() }
        if (extraType != null) return extraType

        val providerClass = AppWidgetManager.getInstance(this)
            .getAppWidgetInfo(appWidgetId)?.provider?.className
        return when (providerClass) {
            HeadlinesGlanceReceiver::class.java.name -> AppWidgetType.HEADLINES
            PriceGlanceReceiver::class.java.name -> AppWidgetType.PRICE
            BlocksGlanceReceiver::class.java.name -> AppWidgetType.BLOCKS
            else -> AppWidgetType.PRICE
        }
    }
}
