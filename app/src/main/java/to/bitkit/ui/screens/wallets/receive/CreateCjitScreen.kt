package to.bitkit.ui.screens.wallets.receive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.Toast
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.appViewModel
import to.bitkit.ui.blocktankViewModel
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.AmountInput
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.shared.util.clickableAlpha
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
    val walletState by wallet.uiState.collectAsStateWithLifecycle()
    val currencies = LocalCurrencies.current

    var satsAmount by remember { mutableLongStateOf(0) }
    var overrideSats: Long? by remember { mutableStateOf(null) }

    var isCreatingInvoice by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        blocktank.refreshMinCjitSats()
    }

    Column(modifier = modifier.fillMaxWidth()) {

        AmountInput(
            primaryDisplay = currencies.primaryDisplay,
            showConversion = true,
            overrideSats = overrideSats,
            onSatsChange = { sats ->
                satsAmount = sats
                overrideSats = null
            },
        )

        Spacer(modifier = Modifier.weight(1f))

        // Actions
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Min amount view
            blocktank.minCjitSats?.let { minCjitSats ->
                Column(
                    modifier = Modifier.clickableAlpha { overrideSats = minCjitSats.toLong() }
                ) {
                    Caption13Up(
                        text = stringResource(R.string.wallet__minimum),
                        color = Colors.White64,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    MoneySSB(sats = minCjitSats.toLong())
                }
            } ?: CircularProgressIndicator(modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.weight(1f))
            UnitButton()
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()

        Spacer(modifier = Modifier.height(16.dp))
        PrimaryButton(
            text = stringResource(R.string.common__continue),
            onClick = {
                val sats = satsAmount.toULong()

                scope.launch {
                    isCreatingInvoice = true

                    if (walletState.nodeLifecycleState == NodeLifecycleState.Starting) {
                        while (walletState.nodeLifecycleState == NodeLifecycleState.Starting && isActive) {
                            delay(500) // 0.5 second delay
                        }
                    }

                    if (walletState.nodeLifecycleState == NodeLifecycleState.Running) {
                        try {
                            val entry = blocktank.createCjit(amountSats = sats, description = "Bitkit")
                            onCjitCreated(entry.invoice.request)
                        } catch (e: Exception) {
                            app.toast(e)
                            Logger.error("Failed to create cjit", e)
                        } finally {
                            isCreatingInvoice = false
                        }
                    } else {
                        app.toast(
                            type = Toast.ToastType.WARNING,
                            title = "Lightning not ready",
                            description = "Lightning node must be running to create an invoice",
                        )
                        isCreatingInvoice = false
                    }
                }
            },
            enabled = !isCreatingInvoice && satsAmount != 0L, // TODO if amount is valid
            isLoading = isCreatingInvoice,
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}
