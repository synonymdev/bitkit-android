package to.bitkit.appwidget

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.ui.calculator.CalculatorGlanceWidget
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalculatorAppWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesStore: AppWidgetPreferencesStore,
) {
    suspend fun update() {
        if (!preferencesStore.hasWidgetsOfType(AppWidgetType.CALCULATOR).first()) return
        CalculatorGlanceWidget().updateAll(context)
    }
}
