package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.models.Toast
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.InfoTextField
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.walletViewModel
import to.bitkit.utils.Logger

@Composable
fun BlocktankRegtestScreen(
    viewModel: BlocktankRegtestViewModel,
    navController: NavController,
) {
    val coroutineScope = rememberCoroutineScope()
    val wallet = walletViewModel ?: return
    val app = appViewModel ?: return
    val uiState by wallet.uiState.collectAsState()

    ScreenColumn {
        AppTopBar(
            titleText = "Blocktank Regtest",
            onBackClick = { navController.popBackStack() },
            actions = { CloseNavIcon(onClick = { navController.navigateToHome() }) },
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
        ) { // State variables for form inputs
            var depositAddress by remember { mutableStateOf(uiState.onchainAddress) }
            var depositAmount by remember { mutableStateOf("123000") }
            var mineBlockCount by remember { mutableStateOf("1") }
            var paymentInvoice by remember { mutableStateOf("") }
            var paymentAmount by remember { mutableStateOf("") }
            var fundingTxId by remember { mutableStateOf("") }
            var vout by remember { mutableStateOf("0") }
            var forceCloseAfter by remember { mutableStateOf("86400") }

            // Flags for loading states
            var isDepositing by remember { mutableStateOf(false) }
            var isMining by remember { mutableStateOf(false) }

            InfoTextField(value = Env.blocktankBaseUrl, label = "Service")
            Text(
                text = "These actions are executed on the staging Blocktank server node.",
                style = MaterialTheme.typography.bodySmall,
            )

            // Deposit Section
            Caption13Up("DEPOSIT", color = Colors.White64, modifier = Modifier.padding(top = 20.dp))
            OutlinedTextField(
                value = depositAddress,
                onValueChange = { depositAddress = it },
                label = { Text(stringResource(R.string.wallet__activity_address)) },
                singleLine = true,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
            )
            OutlinedTextField(
                value = depositAmount,
                onValueChange = { depositAmount = it },
                label = { Text("Amount (sats)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
            )
            PrimaryButton(
                text = if (isDepositing) "Depositing..." else "Make Deposit",
                size = ButtonSize.Small,
                onClick = {
                    coroutineScope.launch {
                        Logger.debug("Initiating regtest deposit with address: $depositAddress, amount: $depositAmount")
                        isDepositing = true
                        try {
                            val sats = depositAmount.toULongOrNull() ?: error("Invalid deposit amount: $depositAmount")
                            val txId = viewModel.regtestDeposit(depositAddress, sats)
                            Logger.debug("Deposit successful with txId: $txId")
                            app.toast(
                                type = Toast.ToastType.SUCCESS,
                                title = "Success",
                                description = "Deposit successful. TxID: $txId",
                            )
                        } catch (e: Exception) {
                            Logger.error("Deposit failed", e)
                            app.toast(
                                type = Toast.ToastType.ERROR,
                                title = "Failed to deposit",
                                description = e.message.orEmpty(),
                            )
                        } finally {
                            isDepositing = false
                            wallet.refreshState()
                        }
                    }
                },
                enabled = depositAddress.isNotEmpty() && !isDepositing,
            )

            // Mining Section
            Caption13Up("MINING", color = Colors.White64, modifier = Modifier.padding(top = 20.dp))
            Row(
                verticalAlignment = CenterVertically, modifier = Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = mineBlockCount,
                    onValueChange = { mineBlockCount = it },
                    placeholder = { Text("Block Count") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            Logger.debug("Starting regtest mining with block count: $mineBlockCount")
                            isMining = true
                            try {
                                val count =
                                    mineBlockCount.toUIntOrNull() ?: error("Invalid block count: $mineBlockCount")
                                viewModel.regtestMine(count)
                                Logger.debug("Successfully mined $count blocks")
                                app.toast(
                                    type = Toast.ToastType.SUCCESS,
                                    title = "Success",
                                    description = "Successfully mined $count blocks",
                                )
                            } catch (e: Exception) {
                                Logger.error("Mining failed", e)
                                app.toast(
                                    type = Toast.ToastType.ERROR,
                                    title = "Failed to mine",
                                    description = e.message.orEmpty(),
                                )
                            } finally {
                                isMining = false
                                wallet.refreshState()
                            }
                        }
                    },
                    enabled = !isMining,
                ) {
                    Text(if (isMining) "Mining..." else "Mine Blocks")
                }
            }

            // Lightning Payment Section
            Caption13Up("LIGHTNING PAYMENT", color = Colors.White64, modifier = Modifier.padding(top = 20.dp))
            OutlinedTextField(
                value = paymentInvoice,
                onValueChange = { paymentInvoice = it },
                label = { Text("Invoice") },
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
            )
            OutlinedTextField(
                value = paymentAmount,
                onValueChange = { paymentAmount = it },
                label = { Text("Amount (optional, sats)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
            )
            PrimaryButton(
                text = "Pay Invoice",
                size = ButtonSize.Small,
                onClick = {
                    coroutineScope.launch {
                        Logger.debug("Initiating regtest payment with invoice: $paymentInvoice, amount: $paymentAmount")
                        try {
                            val amount = if (paymentAmount.isEmpty()) null else paymentAmount.toULongOrNull()
                            val paymentId = viewModel.regtestPay(paymentInvoice, amount)
                            Logger.debug("Payment successful with ID: $paymentId")
                            app.toast(
                                type = Toast.ToastType.SUCCESS,
                                title = "Success",
                                description = "Payment successful. ID: $paymentId",
                            )

                        } catch (e: Exception) {
                            Logger.error("Payment failed", e)
                            app.toast(
                                type = Toast.ToastType.ERROR,
                                title = "Failed to pay invoice from LND",
                                description = e.message.orEmpty(),
                            )
                        }
                    }
                },
                enabled = paymentInvoice.isNotEmpty(),
            )

            // Channel Close Section
            Caption13Up("CHANNEL CLOSE", color = Colors.White64, modifier = Modifier.padding(top = 20.dp))
            OutlinedTextField(
                value = fundingTxId,
                onValueChange = { fundingTxId = it },
                label = { Text("Funding TxID") },
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
            )
            OutlinedTextField(
                value = vout,
                onValueChange = { vout = it },
                label = { Text("Vout") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
            )
            OutlinedTextField(
                value = forceCloseAfter,
                onValueChange = { forceCloseAfter = it },
                label = { Text("Force Close After (seconds)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
            )
            PrimaryButton(
                text = "Close Channel",
                size = ButtonSize.Small,
                onClick = {
                    coroutineScope.launch {
                        Logger.debug("Initiating channel close with fundingTxId: $fundingTxId, vout: $vout, forceCloseAfter: $forceCloseAfter")
                        try {
                            val voutNum = vout.toUIntOrNull() ?: error("Invalid Vout: $vout")
                            val closeAfter =
                                forceCloseAfter.toULongOrNull() ?: error("Invalid Force Close After: $forceCloseAfter")
                            val closingTxId = viewModel.regtestCloseChannel(
                                fundingTxId = fundingTxId,
                                vout = voutNum,
                                forceCloseAfterS = closeAfter,
                            )
                            Logger.debug("Channel closed successfully with txId: $closingTxId")
                            app.toast(
                                type = Toast.ToastType.SUCCESS,
                                title = "Success",
                                description = "Channel closed. Closing TxID: $closingTxId"
                            )
                        } catch (e: Exception) {
                            Logger.error("Channel close failed", e)
                            app.toast(e)
                        }
                    }
                },
                enabled = fundingTxId.isNotEmpty(),
            )
            VerticalSpacer(16.dp)
        }
    }
}
