package to.bitkit.ui.screens.transfer.external

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.AmountInput
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.NumberPadActionButton
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import kotlin.math.min
import kotlin.math.roundToLong

@Composable
fun ExternalAmountScreen(
    viewModel: ExternalNodeViewModel,
    onContinue: () -> Unit,
    onBackClick: () -> Unit,
    onCloseClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Content(
        amountState = uiState.amount,
        onAmountChange = { sats -> viewModel.onAmountChange(sats) },
        onAmountOverride = { sats -> viewModel.onAmountOverride(sats) },
        onContinueClick = {
            viewModel.onAmountContinue()
            onContinue()
        },
        onBackClick = onBackClick,
        onCloseClick = onCloseClick,
    )
}

@Composable
private fun Content(
    amountState: ExternalNodeContract.UiState.Amount = ExternalNodeContract.UiState.Amount(),
    onAmountChange: (Long) -> Unit = {},
    onAmountOverride: (Long) -> Unit = {},
    onContinueClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__external__nav_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onCloseClick) },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .imePadding()
        ) {
            val totalOnchainSats = LocalBalances.current.totalOnchainSats

            Spacer(modifier = Modifier.height(16.dp))
            Display(stringResource(R.string.lightning__external_amount__title).withAccent(accentColor = Colors.Purple))
            Spacer(modifier = Modifier.height(32.dp))

            AmountInput(
                primaryDisplay = LocalCurrencies.current.primaryDisplay,
                overrideSats = amountState.overrideSats,
            ) { sats ->
                onAmountChange(sats)
            }

            Spacer(modifier = Modifier.weight(1f))

            // Actions Row
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Column {
                    Text13Up(
                        text = stringResource(R.string.wallet__send_available),
                        color = Colors.White64,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    MoneySSB(sats = amountState.max)
                }
                Spacer(modifier = Modifier.weight(1f))
                UnitButton(color = Colors.Purple)
                // 25% Button
                NumberPadActionButton(
                    text = stringResource(R.string.lightning__spending_amount__quarter),
                    color = Colors.Purple,
                    onClick = {
                        val quarterOfTotal = (totalOnchainSats.toDouble() / 4.0).roundToLong()
                        val cappedQuarter = min(quarterOfTotal, amountState.max)
                        onAmountOverride(cappedQuarter)
                    },
                )
                // Max Button
                NumberPadActionButton(
                    text = stringResource(R.string.common__max),
                    color = Colors.Purple,
                    onClick = {
                        onAmountOverride(amountState.max)
                    },
                )
            }
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = { onContinueClick() },
                enabled = amountState.sats != 0L,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content()
    }
}
