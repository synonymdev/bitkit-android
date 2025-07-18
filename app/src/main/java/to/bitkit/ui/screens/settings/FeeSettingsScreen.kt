package to.bitkit.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.synonym.bitkitcore.FeeRates
import to.bitkit.R
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsTextButtonRow
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.FeeSettingsUiState
import to.bitkit.viewmodels.FeeSettingsViewModel

@Composable
fun FeeSettingsScreen(
    navController: NavController,
    viewModel: FeeSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshFeeRates()
    }

    Content(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onClose = { navController.navigateToHome() },
    )
}

@Composable
private fun Content(
    uiState: FeeSettingsUiState,
    onBack: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = "Fee Settings",
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            uiState.feeRates?.let { rates ->
                SectionHeader("FEE RATES")

                SettingsTextButtonRow(
                    title = stringResource(R.string.fee__minimum__title),
                    value = "${rates.slow}",
                )
                SettingsTextButtonRow(
                    title = stringResource(R.string.fee__normal__title),
                    value = "${rates.mid}",
                )
                SettingsTextButtonRow(
                    title = stringResource(R.string.fee__fast__title),
                    value = "${rates.fast}",
                )
            }
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = FeeSettingsUiState(
                feeRates = FeeRates(fast = 10u, mid = 5u, slow = 2u)
            ),
        )
    }
}
