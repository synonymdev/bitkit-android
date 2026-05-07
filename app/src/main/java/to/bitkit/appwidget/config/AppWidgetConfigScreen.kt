package to.bitkit.appwidget.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.appwidget.model.AppWidgetType

@Composable
fun AppWidgetConfigScreen(
    viewModel: AppWidgetConfigViewModel,
    onConfirm: suspend () -> Unit,
    onCancel: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when (state.type) {
        AppWidgetType.PRICE -> PriceConfigContent(
            state = state,
            onSelectPair = { viewModel.selectPricePair(it) },
            onSelectPeriod = { viewModel.selectPricePeriod(it) },
            onReset = { viewModel.resetPreferences() },
            onSave = { viewModel.saveAndFinish(onConfirm) },
            onCancel = onCancel,
        )

        AppWidgetType.HEADLINES -> HeadlinesConfigContent(
            state = state,
            onToggleSource = { viewModel.toggleShowSource() },
            onToggleTime = { viewModel.toggleShowTime() },
            onReset = { viewModel.resetPreferences() },
            onSave = { viewModel.saveAndFinish(onConfirm) },
            onCancel = onCancel,
        )

        AppWidgetType.BLOCKS -> BlocksConfigContent(
            state = state,
            onToggleBlock = { viewModel.toggleBlockShowBlock() },
            onToggleTime = { viewModel.toggleBlockShowTime() },
            onToggleDate = { viewModel.toggleBlockShowDate() },
            onToggleTransactions = { viewModel.toggleBlockShowTransactions() },
            onToggleSize = { viewModel.toggleBlockShowSize() },
            onToggleFees = { viewModel.toggleBlockShowFees() },
            onToggleSource = { viewModel.toggleBlockShowSource() },
            onReset = { viewModel.resetPreferences() },
            onSave = { viewModel.saveAndFinish(onConfirm) },
            onCancel = onCancel,
        )

        AppWidgetType.FACTS -> Unit
    }
}
