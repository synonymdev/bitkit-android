package to.bitkit.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.Colors

@Composable
fun CrossChainDemoScreen(
    navController: NavController,
    viewModel: CrossChainDemoViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Content(
        uiState = uiState,
        onBackClick = { navController.popBackStack() },
        onAddressChange = viewModel::updateAddress,
        onAmountChange = viewModel::updateAmount,
        onRouteSelect = viewModel::selectRoute,
        onLoadRoutes = viewModel::loadRoutes,
        onPrepare = viewModel::prepare,
        onSend = viewModel::send,
    )
}

@Composable
private fun Content(
    uiState: CrossChainDemoUiState,
    onBackClick: () -> Unit,
    onAddressChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onRouteSelect: (Int) -> Unit,
    onLoadRoutes: () -> Unit,
    onPrepare: () -> Unit,
    onSend: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }

    ScreenColumn {
        AppTopBar(titleText = "Cross-chain Demo", onBackClick = onBackClick)
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader("DESTINATION ADDRESS", padding = PaddingValues(0.dp))
            TextInput(
                value = uiState.address,
                onValueChange = onAddressChange,
                placeholder = "0x…",
                modifier = Modifier.fillMaxWidth()
            )

            SectionHeader("AMOUNT (SATS)")
            TextInput(
                value = uiState.amountSats,
                onValueChange = onAmountChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            SectionHeader("ROUTES")
            if (uiState.routes.isEmpty()) BodyS("No routes loaded", color = Colors.White64)
            uiState.routes.forEachIndexed { index, route ->
                val selected = index == uiState.selectedRoute
                BodyS(
                    text = (if (selected) "● " else "○ ") + route,
                    color = if (selected) Colors.Brand else Colors.White80,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRouteSelect(index) }
                        .padding(vertical = 6.dp)
                )
            }
            VerticalSpacer(8.dp)
            SecondaryButton(text = "Reload routes", onClick = onLoadRoutes, enabled = !uiState.isBusy)

            VerticalSpacer(16.dp)
            PrimaryButton(
                text = if (uiState.quote == null) "Prepare quote" else "Prepare again",
                onClick = onPrepare,
                isLoading = uiState.isBusy,
                enabled = !uiState.isBusy && uiState.routes.isNotEmpty() && uiState.paymentId == null,
            )

            uiState.quote?.let { quote ->
                SectionHeader("QUOTE")
                QuoteLine("Payee gets", quote.payeeGets)
                QuoteLine("Amount in", quote.amountIn)
                QuoteLine("Fee", quote.fee)
                QuoteLine("Service fee", quote.serviceFee)
                QuoteLine("Spark transfer fee", quote.transferFee)
                QuoteLine("Fee mode", quote.feeMode)
                QuoteLine("Min / max", quote.limits)
                QuoteLine("Expires", quote.expiresAt)
                QuoteLine("Recipient", quote.recipient)
                VerticalSpacer(16.dp)
                PrimaryButton(
                    text = "Send",
                    onClick = { showConfirm = true },
                    enabled = !uiState.isBusy && uiState.paymentId == null,
                )
            }

            uiState.error?.let {
                VerticalSpacer(16.dp)
                BodyS(it, color = Colors.Red)
            }

            uiState.paymentId?.let { id ->
                SectionHeader("STATUS")
                QuoteLine("Payment", id)
                uiState.statusLog.forEach { BodyS(it, color = Colors.White80, modifier = Modifier.padding(vertical = 4.dp)) }
            }
            VerticalSpacer(32.dp)
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Send real money?") },
            text = { Text("This sends ${uiState.amountSats} sats from Spark on mainnet. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onSend()
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun QuoteLine(label: String, value: String) {
    BodyS("$label: $value", modifier = Modifier.padding(vertical = 2.dp))
}
