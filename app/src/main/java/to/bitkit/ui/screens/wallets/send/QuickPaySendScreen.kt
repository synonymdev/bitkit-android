package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.NodeLifecycleState
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.SyncNodeView
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.transfer.components.TransferAnimationView
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.QuickPayResult
import to.bitkit.viewmodels.QuickPayViewModel

@Composable
fun QuickPaySendScreen(
    invoice: String,
    amount: Long,
    onPaymentComplete: () -> Unit,
    onShowError: (String) -> Unit,
    viewModel: QuickPayViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lightningState by viewModel.lightningState.collectAsStateWithLifecycle()

    LaunchedEffect(invoice, lightningState.nodeLifecycleState) {
        if (lightningState.nodeLifecycleState is NodeLifecycleState.Running) {
            viewModel.payInvoice(invoice, amount.toULong())
        }
    }

    LaunchedEffect(uiState.result) {
        when (val result = uiState.result) {
            is QuickPayResult.Success -> onPaymentComplete()
            is QuickPayResult.Error -> onShowError(result.message)
            null -> Unit // continue showing loading state
        }
    }

    QuickPaySendScreenContent(
        amount = amount,
        nodeLifecycleState = lightningState.nodeLifecycleState,
    )
}

@Composable
private fun QuickPaySendScreenContent(
    amount: Long,
    nodeLifecycleState: NodeLifecycleState = NodeLifecycleState.Stopped,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        SheetTopBar(stringResource(R.string.wallet__send_quickpay__nav_title))

        when (nodeLifecycleState) {
            is NodeLifecycleState.Running -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    BalanceHeaderView(sats = amount, modifier = Modifier.fillMaxWidth())

                    Spacer(modifier = Modifier.weight(1f))
                    TransferAnimationView(
                        largeCircleRes = R.drawable.ln_sync_large,
                        smallCircleRes = R.drawable.ln_sync_small,
                        contentRes = R.drawable.coin_stack_4,
                    )
                    Spacer(modifier = Modifier.weight(1f))

                    Display(
                        text = stringResource(R.string.wallet__send_quickpay__title)
                            .withAccent(accentColor = Colors.Purple)
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            else -> {
                SyncNodeView(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        QuickPaySendScreenContent(
            amount = 50000L,
            nodeLifecycleState = NodeLifecycleState.Running,
        )
    }
}

@Preview
@Composable
private fun Preview2() {
    AppThemeSurface {
        QuickPaySendScreenContent(
            amount = 50000L,
            nodeLifecycleState = NodeLifecycleState.Initializing,
        )
    }
}
