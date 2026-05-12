package to.bitkit.appwidget.ui.calculator

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import to.bitkit.appwidget.AppWidgetEntryPoint
import to.bitkit.appwidget.AppWidgetRefreshWorker
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.ui.components.GlanceLayoutDimens
import to.bitkit.models.widget.CalculatorValues
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.CurrencyState
import to.bitkit.ui.screens.widgets.calculator.calculatorSatsToBtcValue
import to.bitkit.ui.screens.widgets.calculator.resolveCalculatorSatsValue

class CalculatorGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(GlanceLayoutDimens.COMPACT_WIDGET_SIZE, GlanceLayoutDimens.WIDE_WIDGET_SIZE),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val accessor = EntryPointAccessors
            .fromApplication(context, AppWidgetEntryPoint::class.java)
        val store = accessor.appWidgetPreferencesStore()
        val widgetsRepo = accessor.widgetsRepo()
        val currencyRepo = accessor.currencyRepo()
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)

        val current = store.data.first()
        val isRegistered = current.entries.any { it.appWidgetId == appWidgetId }
        if (!isRegistered) {
            store.registerWidget(appWidgetId, AppWidgetType.CALCULATOR)
            AppWidgetRefreshWorker.enqueue(context)
        }

        provideContent {
            val widgetsData by widgetsRepo.widgetsDataFlow.collectAsState()
            val currencyState by currencyRepo.currencyState.collectAsState()
            val calculatorValues = remember(widgetsData.calculatorValues, currencyState) {
                resolveCalculatorValues(
                    values = widgetsData.calculatorValues,
                    currencyState = currencyState,
                    currencyRepo = currencyRepo,
                )
            }

            CalculatorGlanceContent(
                values = calculatorValues,
                currencyState = currencyState,
            )
        }
    }

    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        EntryPointAccessors
            .fromApplication(context, AppWidgetEntryPoint::class.java)
            .appWidgetPreferencesStore()
            .unregisterWidget(appWidgetId)
    }

    private fun resolveCalculatorValues(
        values: CalculatorValues,
        currencyState: CurrencyState,
        currencyRepo: CurrencyRepo,
    ): CalculatorValues {
        if (values.btcValue.isEmpty() || values.satsValue == 0L) {
            return values
        }

        val satsValue = values.resolveCalculatorSatsValue()
        if (satsValue == 0L) return values.copy(satsValue = satsValue)

        val btcValue = calculatorSatsToBtcValue(satsValue, currencyState.displayUnit)
        val fiatValue = currencyRepo.convertSatsToFiat(satsValue).getOrNull()?.formatted.orEmpty()
        if (fiatValue.isEmpty()) {
            return values.copy(
                btcValue = btcValue,
                satsValue = satsValue,
                displayUnit = currencyState.displayUnit,
            )
        }

        return values.copy(
            btcValue = btcValue,
            fiatValue = fiatValue,
            satsValue = satsValue,
            displayUnit = currencyState.displayUnit,
        )
    }
}
