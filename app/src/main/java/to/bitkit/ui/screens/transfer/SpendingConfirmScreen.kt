package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.ui.appViewModel
import to.bitkit.ui.blocktankViewModel
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.FullWidthTextButton
import to.bitkit.ui.shared.moneyString
import to.bitkit.ui.shared.util.onLongPress
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.TransferViewModel
import uniffi.bitkitcore.IBtOrder

@Composable
fun SpendingConfirmScreen(
    viewModel: TransferViewModel,
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    val app = appViewModel ?: return
    val blocktank = blocktankViewModel ?: return
    val scope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val order = state.order ?: return

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__transfer__nav_title),
            onBackClick = onBackClick,
            actions = {
                IconButton(onClick = onCloseClick) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.common__close),
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()

        ) {
            Spacer(modifier = Modifier.height(32.dp))
            Display(text = stringResource(R.string.lightning__transfer__confirm).withAccent(accentColor = Colors.Purple))
            Spacer(modifier = Modifier.height(32.dp))
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                OrderSummary(order)
            }
            Spacer(modifier = Modifier.height(24.dp))
            if (state.txId == null) {
                Spacer(modifier = Modifier.weight(1f))
                var isPaying by remember { mutableStateOf(false) }
                PrimaryButton(
                    text = "Confirm",
                    onClick = {
                        isPaying = true
                        viewModel.payOrder(state.order!!)
                    },
                    enabled = !isPaying,
                )
            } else {
                Card {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text("✅ Payment sent", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "TxId: ${state.txId}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "You can close the app now. We will notify you when the channel is ready.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                FullWidthTextButton(
                    onClick = {
                        scope.launch {
                            try {
                                blocktank.open(orderId = order.id)
                                Logger.info("Channel opened for order ${order.id}")
                                app.toast(Toast.ToastType.SUCCESS, "Success", "Manual open success")
                            } catch (e: Throwable) {
                                Logger.error("Error opening channel for order ${order.id}", e)
                                app.toast(e)
                            }
                        }
                    },
                ) {
                    Text(text = "Try manual open")
                }
            }
        }
    }
}

@Composable
private fun OrderSummary(order: IBtOrder) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(12.dp)
    ) {
        val clipboardManager = LocalClipboardManager.current
        Text(
            text = order.id,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .onLongPress { clipboardManager.setText(AnnotatedString(order.id)) }
                .padding(bottom = 8.dp)
        )
        Text(
            text = "Fees: " + moneyString(order.feeSat.toLong()),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "Spending: " + moneyString(order.clientBalanceSat.toLong()),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "Receiving: " + moneyString(order.lspBalanceSat.toLong()),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "State: ${order.state2}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
