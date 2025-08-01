package to.bitkit.ui.settings.advanced

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

object CoinSelectPreferenceTestTags {
    const val SCREEN = "coin_select_preference_screen"
    const val MANUAL_BUTTON = "manual_button"
    const val AUTOPILOT_BUTTON = "autopilot_button"
    const val LARGEST_FIRST_BUTTON = "largest_first_button"
    const val CONSOLIDATE_BUTTON = "consolidate_button"
    const val FIRST_IN_FIRST_OUT_BUTTON = "first_in_first_out_button"
    const val BRANCH_AND_BOUND_BUTTON = "branch_and_bound_button"
    const val SINGLE_RANDOM_DRAW_BUTTON = "single_random_draw_button"
}

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
        onClickManual = { viewModel.setAutoMode(false) },
        onClickAutopilot = { viewModel.setAutoMode(true) },
        onClickCoinSelectionPreference = { preference -> viewModel.setCoinSelectionPreference(preference) },
    )
}

@Composable
private fun Content(
    uiState: CoinSelectPreferenceUiState,
    onBack: () -> Unit = {},
    onClose: () -> Unit = {},
    onClickManual: () -> Unit = {},
    onClickAutopilot: () -> Unit = {},
    onClickCoinSelectionPreference: (CoinSelectionPreference) -> Unit = {},
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
                .testTag(CoinSelectPreferenceTestTags.SCREEN)
        ) {
            SectionHeader(title = stringResource(R.string.settings__adv__cs_method))

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__cs_manual),
                value = SettingsButtonValue.BooleanValue(!uiState.isAutoPilot),
                onClick = onClickManual,
                modifier = Modifier.testTag(CoinSelectPreferenceTestTags.MANUAL_BUTTON),
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__cs_auto),
                value = SettingsButtonValue.BooleanValue(uiState.isAutoPilot),
                onClick = onClickAutopilot,
                modifier = Modifier.testTag(CoinSelectPreferenceTestTags.AUTOPILOT_BUTTON),
            )

            if (uiState.isAutoPilot) {
                SectionHeader(title = stringResource(R.string.settings__adv__cs_auto_mode))

                // TODO uncomment if available or implementing custom coin selection logic
                // SettingsButtonRow(
                //     title = stringResource(R.string.settings__adv__cs_max),
                //     description = stringResource(R.string.settings__adv__cs_max_description),
                //     value = SettingsButtonValue.BooleanValue(
                //         uiState.coinSelectionPreference == CoinSelectionPreference.SmallestFirst
                //     ),
                //     onClick = { onClickCoinSelectionPreference(CoinSelectionPreference.SmallestFirst) },
                // )

                SettingsButtonRow(
                    title = stringResource(R.string.settings__adv__cs_min),
                    description = stringResource(R.string.settings__adv__cs_min_description),
                    value = SettingsButtonValue.BooleanValue(
                        uiState.coinSelectionPreference == CoinSelectionPreference.LargestFirst
                    ),
                    onClick = { onClickCoinSelectionPreference(CoinSelectionPreference.LargestFirst) },
                    modifier = Modifier.testTag(CoinSelectPreferenceTestTags.LARGEST_FIRST_BUTTON),
                )

                SettingsButtonRow(
                    title = stringResource(R.string.settings__adv__cs_consolidate),
                    description = stringResource(R.string.settings__adv__cs_consolidate_description),
                    value = SettingsButtonValue.BooleanValue(
                        uiState.coinSelectionPreference == CoinSelectionPreference.Consolidate
                    ),
                    onClick = { onClickCoinSelectionPreference(CoinSelectionPreference.Consolidate) },
                    modifier = Modifier.testTag(CoinSelectPreferenceTestTags.CONSOLIDATE_BUTTON),
                )

                SettingsButtonRow(
                    title = stringResource(R.string.settings__adv__cs_first_in_first_out),
                    description = stringResource(R.string.settings__adv__cs_first_in_first_out_description),
                    value = SettingsButtonValue.BooleanValue(
                        uiState.coinSelectionPreference == CoinSelectionPreference.FirstInFirstOut
                    ),
                    onClick = { onClickCoinSelectionPreference(CoinSelectionPreference.FirstInFirstOut) },
                    modifier = Modifier.testTag(CoinSelectPreferenceTestTags.FIRST_IN_FIRST_OUT_BUTTON),
                )

                // TODO uncomment if available or implementing custom coin selection logic
                // SettingsButtonRow(
                //     title = stringResource(R.string.settings__adv__cs_last_in_last_out),
                //     description = stringResource(R.string.settings__adv__cs_last_in_last_out_description),
                //     value = SettingsButtonValue.BooleanValue(
                //         uiState.coinSelectionPreference == CoinSelectionPreference.LastInFirstOut
                //     ),
                //     onClick = { onClickCoinSelectionPreference(CoinSelectionPreference.LastInFirstOut) },
                // )

                SettingsButtonRow(
                    title = "Branch and Bound", // TODO add missing localized text
                    description = "Finds exact amount matches to minimize change", // TODO add missing localized text
                    value = SettingsButtonValue.BooleanValue(
                        uiState.coinSelectionPreference == CoinSelectionPreference.BranchAndBound
                    ),
                    onClick = { onClickCoinSelectionPreference(CoinSelectionPreference.BranchAndBound) },
                    modifier = Modifier.testTag(CoinSelectPreferenceTestTags.BRANCH_AND_BOUND_BUTTON),
                )

                SettingsButtonRow(
                    title = "Single Random Draw", // TODO add missing localized text
                    description = "Random selection for privacy", // TODO add missing localized text
                    value = SettingsButtonValue.BooleanValue(
                        uiState.coinSelectionPreference == CoinSelectionPreference.SingleRandomDraw
                    ),
                    onClick = { onClickCoinSelectionPreference(CoinSelectionPreference.SingleRandomDraw) },
                    modifier = Modifier.testTag(CoinSelectPreferenceTestTags.SINGLE_RANDOM_DRAW_BUTTON),
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
