package to.bitkit.ui.screens.wallets.withdraw

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
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
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.SendUiState

@Composable
fun WithdrawConfirmScreen(
    uiState: SendUiState,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        SheetTopBar(stringResource(R.string.wallet__lnurl_w_title)) {
            onBack()
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(16.dp)
            BalanceHeaderView(sats = uiState.amount.toLong(), modifier = Modifier.fillMaxWidth())
            VerticalSpacer(46.dp)

            BodyM(
                text = stringResource(R.string.wallet__lnurl_w_text),
                color = Colors.White64,
            )

            Image(
                painter = painterResource(R.drawable.transfer),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .aspectRatio(1.0f)
                    .weight(1f)
            )

            PrimaryButton(
                text = stringResource(R.string.wallet__lnurl_w_button),
                onClick = onConfirm,
                isLoading = uiState.isLoading,
                modifier = Modifier.testTag("continue_button")
            )
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
            WithdrawConfirmScreen(
                uiState = SendUiState(
                    amount = 250_000u
                ),
                onBack = {},
                onConfirm = {},
            )
        }
    }
}
