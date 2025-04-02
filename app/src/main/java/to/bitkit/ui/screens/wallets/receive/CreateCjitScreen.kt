package to.bitkit.ui.screens.wallets.receive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.blocktankViewModel
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.walletViewModel
import to.bitkit.utils.Logger

@Composable
fun CreateCjitScreen(
    modifier: Modifier = Modifier,
    onCjitCreated: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DisposableEffect(Unit) {
        onDispose {
            onDismiss()
        }
    }

    val app = appViewModel ?: return
    val wallet = walletViewModel ?: return
    val blocktank = blocktankViewModel ?: return
    val walletUiState by wallet.uiState.collectAsStateWithLifecycle()

    var amount by remember { mutableStateOf("") }
    var isCreatingInvoice by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxWidth()) {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        TextField(
            placeholder = {
                Text("Amount in sats", style = MaterialTheme.typography.titleLarge)
            },
            value = amount,
            onValueChange = { amount = it },
            colors = AppTextFieldDefaults.transparent,
            shape = MaterialTheme.shapes.small,
            textStyle = MaterialTheme.typography.titleLarge,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
        Spacer(modifier = Modifier.weight(1f))

        blocktank.info?.let { info ->
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Min amount view
                Column(
                    modifier = Modifier
                        .clickableAlpha {
                            amount = (info.options.minChannelSizeSat / 2u).toString()
                        }
                ) {
                    Caption13Up(
                        text = stringResource(R.string.wallet__minimum),
                        color = Colors.White64,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    MoneySSB(sats = (info.options.minChannelSizeSat / 2u).toLong())
                }

                UnitButton()
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()

        Spacer(modifier = Modifier.height(16.dp))
        PrimaryButton(
            text = stringResource(R.string.common__continue),
            onClick = {
                if (walletUiState.nodeId.isEmpty()) return@PrimaryButton
                amount.toULongOrNull()?.let { amountValue ->
                    scope.launch {
                        isCreatingInvoice = true
                        try {
                            val entry = blocktank.createCjit(amountSats = amountValue, description = "Bitkit")
                            onCjitCreated(entry.invoice.request)
                        } catch (e: Exception) {
                            Logger.error("Failed to create cjit", e)
                            app.toast(e)
                        } finally {
                            isCreatingInvoice = false
                        }
                    }
                }
            }
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}
