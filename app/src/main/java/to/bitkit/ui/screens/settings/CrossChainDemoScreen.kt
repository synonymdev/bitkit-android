package to.bitkit.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import to.bitkit.ext.nowMillis
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.Colors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

@Composable
fun CrossChainDemoScreen(
    navController: NavController,
    viewModel: CrossChainDemoViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Content(
        uiState = uiState,
        onBackClick = { navController.popBackStack() },
        onModeSelect = viewModel::selectMode,
        onAddressChange = viewModel::updateAddress,
        onAmountChange = viewModel::updateAmount,
        onRouteSelect = viewModel::selectRoute,
        onLoadRoutes = viewModel::loadRoutes,
        onEstimateSavings = viewModel::estimateSavings,
        onFund = viewModel::fund,
        onPrepare = viewModel::prepare,
        onSend = viewModel::send,
    )
}

private enum class Confirm { Fund, Send }

@Composable
private fun Content(
    uiState: CrossChainDemoUiState,
    onBackClick: () -> Unit,
    onModeSelect: (CrossChainMode) -> Unit,
    onAddressChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onRouteSelect: (Int) -> Unit,
    onLoadRoutes: () -> Unit,
    onEstimateSavings: () -> Unit,
    onFund: () -> Unit,
    onPrepare: () -> Unit,
    onSend: () -> Unit,
) {
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var showRoutes by remember { mutableStateOf(false) }
    val isSavings = uiState.mode == CrossChainMode.Savings

    ScreenColumn {
        AppTopBar(titleText = "Cross-chain Demo", onBackClick = onBackClick)
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CrossChainMode.entries.forEach { mode ->
                    val text = if (mode == CrossChainMode.Spark) "From Spending" else "From Savings"
                    if (mode == uiState.mode) {
                        PrimaryButton(
                            text = text,
                            onClick = {},
                            size = ButtonSize.Small,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        SecondaryButton(
                            text = text,
                            onClick = { onModeSelect(mode) },
                            enabled = !uiState.isLocked,
                            size = ButtonSize.Small,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Caption("Destination")
            TextInput(
                value = uiState.address,
                onValueChange = onAddressChange,
                placeholder = "0x…",
                enabled = !uiState.isLocked,
                modifier = Modifier.fillMaxWidth()
            )

            Caption(if (isSavings && uiState.claimedSats == null) "You send from Savings (sats)" else "You send (sats)")
            TextInput(
                value = uiState.amountSats,
                onValueChange = onAmountChange,
                singleLine = true,
                enabled = uiState.paymentId == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            DetailRow(
                title = "Route",
                value = uiState.routes.getOrNull(uiState.selectedRoute) ?: "No routes loaded",
                modifier = Modifier.clickable(enabled = !uiState.isLocked) { showRoutes = !showRoutes }
            )
            if (showRoutes) {
                uiState.routes.forEachIndexed { index, route ->
                    BodyM(
                        text = route,
                        color = if (index == uiState.selectedRoute) Colors.Brand else Colors.White80,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onRouteSelect(index)
                                showRoutes = false
                            }
                            .padding(vertical = 10.dp)
                    )
                }
                SecondaryButton(
                    text = "Reload routes",
                    onClick = onLoadRoutes,
                    enabled = !uiState.isBusy,
                    size = ButtonSize.Small,
                )
            }

            if (isSavings) SavingsSection(uiState, onEstimateSavings) { confirm = Confirm.Fund }

            if (uiState.isLeg2) {
                if (isSavings) Section("Leg 2 · Spending to ${uiState.routes.getOrNull(uiState.selectedRoute)}")
                VerticalSpacer(16.dp)
                PrimaryButton(
                    text = if (uiState.quote == null) "Prepare quote" else "Prepare again",
                    onClick = onPrepare,
                    isLoading = uiState.isBusy,
                    enabled = !uiState.isBusy && uiState.routes.isNotEmpty() && uiState.paymentId == null,
                )
                uiState.quote?.let { QuoteSection(it, uiState, onSend = { confirm = Confirm.Send }) }
            }

            uiState.error?.let {
                VerticalSpacer(16.dp)
                BodyS(it, color = Colors.Red)
            }

            if (uiState.paymentId != null) {
                Section("Order")
                DetailRow("Payment", "${uiState.paymentStatus ?: "?"} · ${uiState.paymentId}")
                DetailRow("Order id", uiState.orderId ?: "waiting for Orchestra")
                DetailRow("Order status", uiState.orderStatus ?: "?")
                uiState.delivered?.let { DetailRow("Delivered (base units)", it) }
                uiState.deliveryTx?.let { DetailRow("Delivery tx", it) }
            }
            if (uiState.statusLog.isNotEmpty()) {
                Section("Log")
                uiState.statusLog.forEach {
                    BodyS(it, color = Colors.White64, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
            VerticalSpacer(32.dp)
        }
    }

    confirm?.let { kind ->
        val text = when (kind) {
            Confirm.Fund -> "This sends ${uiState.amountSats} sats on-chain from Savings to this wallet's own Spark " +
                "deposit address ${uiState.savings?.depositAddress}, with a mining fee of ${uiState.savings?.miningFee}." +
                " It cannot be undone."
            Confirm.Send -> "This sends ${uiState.quote?.youSend} from Spending on mainnet to " +
                "${uiState.quote?.recipient}. It cannot be undone."
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Send real money?") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (kind == Confirm.Fund) onFund() else onSend()
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SavingsSection(uiState: CrossChainDemoUiState, onEstimate: () -> Unit, onFund: () -> Unit) {
    VerticalSpacer(16.dp)
    if (uiState.fundingTxid == null) {
        PrimaryButton(
            text = if (uiState.savings == null) "Estimate" else "Estimate again",
            onClick = onEstimate,
            isLoading = uiState.isBusy,
            enabled = !uiState.isBusy && uiState.routes.isNotEmpty(),
        )
    }
    val estimate = uiState.savings ?: return
    Section("Leg 1 · Savings to own Spark")
    DetailRow("Deposit address (own Spark)", estimate.depositAddress)
    DetailPair("Mining fee", estimate.miningFee, "Spark claim fee", estimate.claimFee)
    DetailPair("Spark gets", estimate.sparkGets, "Instant claim instead", estimate.instantClaimCeiling)
    DetailRow("Wait", "3 confirmations (~30 min) before Spark claims it")
    Section("Leg 2 estimate · quoted again after the claim")
    DetailPair("Payee gets", estimate.leg2PayeeGets, "Fees", estimate.leg2Fees)
    Section("Orchestra direct · estimate only, needs a partner key")
    DetailPair("Payee gets", estimate.directPayeeGets, "Fee", estimate.directFee)
    DetailRow("Route", estimate.directRoute)
    DetailRow("Minimum", "6,188 sat ($0.80)")
    if (uiState.fundingTxid == null) {
        VerticalSpacer(16.dp)
        PrimaryButton(
            text = "Send to own Spark",
            onClick = onFund,
            enabled = !uiState.isBusy && estimate.miningFeeSats != null,
        )
    } else {
        DetailRow("Funding tx", uiState.fundingTxid)
        DetailRow("Claimed", uiState.claimedSats?.let { "$it sat deposit" } ?: "waiting for 3 confirmations")
    }
}

@Composable
private fun QuoteSection(quote: CrossChainQuote, uiState: CrossChainDemoUiState, onSend: () -> Unit) {
    Section("Quote")
    DetailRow("Route", quote.route)
    DetailPair("You send", quote.youSend, "Payee gets", quote.payeeGets, valueColor = Colors.Brand)
    DetailPair("Fee", quote.fee, "Service fee", quote.serviceFee)
    DetailPair("Spark transfer fee", quote.transferFee, "Fee mode", quote.feeMode)
    DetailRow("Min / max", quote.limits)
    DetailRow("Quote expires", expiryText(quote.expiresAtMillis), valueColor = expiryColor(quote.expiresAtMillis))
    DetailRow("Recipient", quote.recipient)
    VerticalSpacer(16.dp)
    PrimaryButton(
        text = "Send",
        onClick = onSend,
        enabled = !uiState.isBusy && uiState.paymentId == null,
    )
}

@Composable
private fun rememberNow(): Long {
    val now by produceState(nowMillis()) {
        while (true) {
            delay(1.seconds)
            value = nowMillis()
        }
    }
    return now
}

@Composable
private fun expiryText(expiresAtMillis: Long?): String {
    if (expiresAtMillis == null) return "?"
    val left = (expiresAtMillis - rememberNow()) / 1000
    val local = remember(expiresAtMillis) {
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(expiresAtMillis))
    }
    return if (left > 0) "$local (in ${left / 60}:${"%02d".format(left % 60)})" else "$local (expired, prepare again)"
}

@Composable
private fun expiryColor(expiresAtMillis: Long?): Color =
    if (expiresAtMillis != null && expiresAtMillis <= rememberNow()) Colors.Red else Colors.White

@Composable
private fun Section(title: String) {
    VerticalSpacer(24.dp)
    BodySSB(title, color = Colors.White)
}

@Composable
private fun Caption(title: String) {
    Caption13Up(title, color = Colors.White64, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
private fun DetailRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Colors.White,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Caption(title)
        BodySSB(value, color = valueColor)
        VerticalSpacer(16.dp)
        HorizontalDivider()
    }
}

@Composable
private fun DetailPair(
    leftTitle: String,
    leftValue: String,
    rightTitle: String,
    rightValue: String,
    valueColor: Color = Colors.White,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        DetailRow(leftTitle, leftValue, valueColor = valueColor, modifier = Modifier.weight(1f))
        DetailRow(rightTitle, rightValue, valueColor = valueColor, modifier = Modifier.weight(1f))
    }
}
