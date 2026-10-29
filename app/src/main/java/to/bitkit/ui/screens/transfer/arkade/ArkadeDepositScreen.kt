package to.bitkit.ui.screens.transfer.arkade

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.transfer.arkade.ArkadeFundingContract.Deposit
import to.bitkit.ui.screens.transfer.arkade.ArkadeFundingContract.UiState
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun ArkadeDepositScreen(
    viewModel: ArkadeFundingViewModel,
    onContinueClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Content(
        uiState = uiState,
        onContinueClick = onContinueClick,
    )
}

@Composable
private fun Content(
    uiState: UiState,
    onContinueClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(titleText = stringResource(R.string.lightning__transfer__nav_title), onBackClick = null)
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .testTag("ArkadeDeposit")
        ) {
            VerticalSpacer(32.dp)
            Display(stringResource(R.string.lightning__savings_progress__title).withAccent(accentColor = Colors.Purple))
            VerticalSpacer(8.dp)

            val text = when (uiState.deposit) {
                Deposit.Looking -> R.string.lightning__arkade_deposit__looking
                Deposit.Boarding -> R.string.lightning__arkade_deposit__boarding
                Deposit.Boarded -> R.string.lightning__arkade_deposit__boarded
            }
            BodyM(text = stringResource(text), color = Colors.White64)

            FillHeight()

            Image(
                painter = painterResource(id = R.drawable.hourglass),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(256.dp)
                    .align(alignment = CenterHorizontally)
            )

            SecondaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onContinueClick,
                enabled = !uiState.isLoading,
                modifier = Modifier.testTag("ArkadeDepositContinue")
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(uiState = UiState(deposit = Deposit.Boarding))
    }
}
