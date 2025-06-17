package to.bitkit.ui.settings.advanced

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.models.CoinSelectionPreference
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun CoinSelectPreferenceScreen(
    navController: NavController,
    viewModel: CoinSelectPreferenceViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Content(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onClose = { navController.navigateToHome() },
        onManualModeClick = { viewModel.setAutoMode(false) },
        onAutopilotModeClick = { viewModel.setAutoMode(true) },
        onSmallestFirstClick = { viewModel.setCoinSelectionPreference(CoinSelectionPreference.SmallestFirst) },
        onLargestFirstClick = { viewModel.setCoinSelectionPreference(CoinSelectionPreference.LargestFirst) },
        onConsolidateClick = { viewModel.setCoinSelectionPreference(CoinSelectionPreference.Consolidate) },
        onFirstInFirstOutClick = { viewModel.setCoinSelectionPreference(CoinSelectionPreference.FirstInFirstOut) },
        onLastInFirstOutClick = { viewModel.setCoinSelectionPreference(CoinSelectionPreference.LastInFirstOut) },
    )
}

@Composable
private fun Content(
    uiState: CoinSelectPreferenceUiState,
    onBack: () -> Unit = {},
    onClose: () -> Unit = {},
    onManualModeClick: () -> Unit = {},
    onAutopilotModeClick: () -> Unit = {},
    onSmallestFirstClick: () -> Unit = {},
    onLargestFirstClick: () -> Unit = {},
    onConsolidateClick: () -> Unit = {},
    onFirstInFirstOutClick: () -> Unit = {},
    onLastInFirstOutClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__adv__coin_selection),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClose) },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader(title = stringResource(R.string.settings__adv__cs_method))

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__cs_manual),
                value = SettingsButtonValue.BooleanValue(!uiState.isAutoPilot),
                onClick = onManualModeClick,
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__cs_auto),
                value = SettingsButtonValue.BooleanValue(uiState.isAutoPilot),
                onClick = onAutopilotModeClick,
            )

            // TODO use existing CoinSelectionAlgorithm from ldk-node fork instead
            if (uiState.isAutoPilot) {
                SectionHeader(title = stringResource(R.string.settings__adv__cs_auto_mode))

                SettingsButtonRow(
                    title = stringResource(R.string.settings__adv__cs_max),
                    description = stringResource(R.string.settings__adv__cs_max_description),
                    value = SettingsButtonValue.BooleanValue(uiState.coinSelectionPreference == CoinSelectionPreference.SmallestFirst),
                    onClick = onSmallestFirstClick,
                )

                SettingsButtonRow(
                    title = stringResource(R.string.settings__adv__cs_min),
                    description = stringResource(R.string.settings__adv__cs_min_description),
                    value = SettingsButtonValue.BooleanValue(uiState.coinSelectionPreference == CoinSelectionPreference.LargestFirst),
                    onClick = onLargestFirstClick,
                )

                SettingsButtonRow(
                    title = stringResource(R.string.settings__adv__cs_consolidate),
                    description = stringResource(R.string.settings__adv__cs_consolidate_description),
                    value = SettingsButtonValue.BooleanValue(uiState.coinSelectionPreference == CoinSelectionPreference.Consolidate),
                    onClick = onConsolidateClick,
                )

                SettingsButtonRow(
                    title = stringResource(R.string.settings__adv__cs_first_in_first_out),
                    description = stringResource(R.string.settings__adv__cs_first_in_first_out_description),
                    value = SettingsButtonValue.BooleanValue(uiState.coinSelectionPreference == CoinSelectionPreference.FirstInFirstOut),
                    onClick = onFirstInFirstOutClick,
                )

                SettingsButtonRow(
                    title = stringResource(R.string.settings__adv__cs_last_in_last_out),
                    description = stringResource(R.string.settings__adv__cs_last_in_last_out_description),
                    value = SettingsButtonValue.BooleanValue(uiState.coinSelectionPreference == CoinSelectionPreference.LastInFirstOut),
                    onClick = onLastInFirstOutClick,
                )
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = CoinSelectPreferenceUiState(
                isAutoPilot = true,
            ),
        )
    }
}

@Preview
@Composable
private fun Preview2() {
    AppThemeSurface {
        Content(
            uiState = CoinSelectPreferenceUiState(
                isAutoPilot = false,
            ),
        )
    }
}
