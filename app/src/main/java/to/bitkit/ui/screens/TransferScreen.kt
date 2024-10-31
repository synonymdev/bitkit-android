package to.bitkit.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.scaffold.AppScaffold
import to.bitkit.ui.shared.FullWidthTextButton
import to.bitkit.ui.shared.OrderSummary

@Composable
fun TransferScreen(
    walletViewModel: WalletViewModel,
    navController: NavController,
    viewModel: TransferViewModel = hiltViewModel(),
) = AppScaffold(navController, walletViewModel, "Transfer Funds") {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        when (val state = uiState.value) {
            is TransferUiState.Create -> CreateView(viewModel)
            is TransferUiState.Confirm -> ConfirmView(state, viewModel)
        }
    }
}

@Composable
private fun CreateView(viewModel: TransferViewModel) {
    var isCreating by remember { mutableStateOf(false) }
    var spendingBalanceSats by remember { mutableIntStateOf(50_000) }
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
            viewModel.createOrder(spendingBalanceSats)
        },
        enabled = !isCreating,
    ) {
        Text(if (isCreating) "Creating order..." else "Continue")
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
            enabled = !isPaying
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
            onClick = { viewModel.manualOpenChannel(state.order) },
        ) {
            Text(text = "Try manual open")
        }
    }
}
