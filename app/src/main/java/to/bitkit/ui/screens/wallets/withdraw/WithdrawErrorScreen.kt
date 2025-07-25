package to.bitkit.ui.screens.wallets.withdraw

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import to.bitkit.ui.shared.util.gradientBackground
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        SheetTopBar(stringResource(R.string.other__lnurl_withdr_error)) {
            onBack()
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(16.dp)
            BalanceHeaderView(sats = uiState.amount.toLong(), modifier = Modifier.fillMaxWidth())

            VerticalSpacer(46.dp)

            BodyM( // TODO add missing localized text
                text = "Your withdrawal was unsuccessful. Please scan the QR code again or contact support.",
                color = Colors.White64,
            )

            Image(
                painter = painterResource(R.drawable.exclamation_mark),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .aspectRatio(1.0f)
                    .weight(1f),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SecondaryButton(
                    text = stringResource(R.string.lightning__support),
                    onClick = onClickSupport,
                    fullWidth = false,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("support_button"),
                )

                PrimaryButton(
                    text = stringResource(R.string.wallet__recipient_scan),
                    onClick = onClickScan,
                    fullWidth = false,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("scan_button")
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Column {
            VerticalSpacer(100.dp)
            WithDrawErrorScreen(
                uiState = SendUiState(
                    amount = 250_000u
                ),
                onBack = {},
                onClickScan = {},
                onClickSupport = {},
            )
        }
    }
}
