package to.bitkit.ui.screens.wallets.withdraw

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.SendUiState

@Composable
fun WithDrawErrorScreen(
    uiState: SendUiState,
    onBack: () -> Unit,
    onClickScan: () -> Unit,
    onClickSupport: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SheetTopBar(stringResource(R.string.other__lnurl_withdr_error)) {
            onBack()
        }

        VerticalSpacer(16.dp)

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
        ) {
            BalanceHeaderView(sats = uiState.amount.toLong(), modifier = Modifier.fillMaxWidth())

            VerticalSpacer(46.dp)

            BodyM( //TODO Doesn't exist in resources
                text = "Your withdrawal was unsuccessful. Please scan the QR code again or contact support.",
                color = Colors.White64,
                modifier = Modifier.fillMaxWidth()
            )

            Image(
                painter = painterResource(R.drawable.exclamation_mark),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .aspectRatio(1.0f)
                    .weight(1f),
                contentDescription = null
            )

            Row {
                SecondaryButton(
                    text = stringResource(R.string.lightning__support),
                    onClick = onClickSupport,
                    modifier = Modifier.testTag("support_button")
                )
                PrimaryButton(
                    text = stringResource(R.string.wallet__recipient_scan),
                    onClick = onClickScan,
                    modifier = Modifier.testTag("scan_button")
                )
            }

            VerticalSpacer(16.dp)
        }

    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        WithDrawErrorScreen(
            uiState = SendUiState(
                amount = 250000UL
            ),
            onBack = {},
            onClickScan = {},
            onClickSupport = {},
        )
    }
}
