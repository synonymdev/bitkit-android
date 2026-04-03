package to.bitkit.appwidget.config

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import to.bitkit.appwidget.AppWidgetRefreshWorker
import to.bitkit.appwidget.model.AppWidgetType
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

        setResult(Activity.RESULT_CANCELED)

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val typeName = intent?.getStringExtra(EXTRA_WIDGET_TYPE)
        val type = typeName?.let { runCatching { AppWidgetType.valueOf(it) }.getOrNull() }
            ?: resolveTypeFromProvider()
            ?: AppWidgetType.BLOCKS

        viewModel.init(appWidgetId, type)

        setContent {
            AppThemeSurface {
                AppWidgetConfigScreen(
                    viewModel = viewModel,
                    onConfirm = {
                        AppWidgetRefreshWorker.enqueueImmediate(this)
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

    private fun resolveTypeFromProvider(): AppWidgetType? {
        val providerInfo = intent?.extras?.let {
            val id = it.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            if (id != -1) AppWidgetManager.getInstance(this).getAppWidgetInfo(id) else null
        } ?: return null

        val providerClass = providerInfo.provider.className
        return when {
            providerClass.contains("Blocks") -> AppWidgetType.BLOCKS
            providerClass.contains("Price") -> AppWidgetType.PRICE
            providerClass.contains("Weather") -> AppWidgetType.WEATHER
            providerClass.contains("Headlines") -> AppWidgetType.HEADLINES
            providerClass.contains("Facts") -> AppWidgetType.FACTS
            else -> null
        }
    }
}
