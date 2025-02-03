package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.blocktankViewModel
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.FullWidthTextButton
import to.bitkit.ui.shared.moneyString
import to.bitkit.ui.shared.util.onLongPress
import uniffi.bitkitcore.IBtOrder

@Composable
fun TransferScreen(
    viewModel: TransferViewModel,
    navController: NavController,
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle()
    ScreenColumn {
        AppTopBar(
            stringResource(R.string.transfer_funds),
            onBackClick = { navController.popBackStack() })
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxSize()
        ) {
            when (val state = uiState.value) {
                is TransferUiState.Create -> CreateView(viewModel)
                is TransferUiState.Confirm -> ConfirmView(state, viewModel)
            }
        }
    }
}

@Composable
private fun CreateView(viewModel: TransferViewModel) {
    var isCreating by remember { mutableStateOf(false) }
    var spendingBalanceSats by remember { mutableIntStateOf(50_000) }
    val scope = rememberCoroutineScope()
    val app = appViewModel ?: return
    val blocktank = blocktankViewModel ?: return

    OutlinedTextField(
        label = { Text("Sats") },
        value = "$spendingBalanceSats",
        onValueChange = { spendingBalanceSats = it.toIntOrNull() ?: 0 },
        textStyle = MaterialTheme.typography.labelSmall,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    FullWidthTextButton(
        onClick = {
            isCreating = true
            scope.launch {
                try {
                    val order = blocktank.createOrder(spendingBalanceSats.toULong())
                    viewModel.onOrderCreated(order)
                } catch (e: Throwable) {
                    app.toast(e)
                } finally {
                    isCreating = false
                }
            }
        },
        enabled = !isCreating,
        loading = isCreating,
    ) {
        Text("Continue")
    }
}

@Composable
private fun ConfirmView(
    state: TransferUiState.Confirm,
    viewModel: TransferViewModel,
) {
    Text(
        text = "Confirm Order",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
    Spacer(modifier = Modifier.height(24.dp))
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        OrderSummary(state.order)
    }
    Spacer(modifier = Modifier.height(24.dp))
    if (state.txId == null) {
        var isPaying by remember { mutableStateOf(false) }
        FullWidthTextButton(
            onClick = {
                isPaying = true
                viewModel.payOrder(state.order)
            },
            enabled = !isPaying,
        ) {
            Text("Confirm")
        }
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
                viewModel.manualOpenChannel(state.order)
            },
        ) {
            Text(text = "Try manual open")
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
