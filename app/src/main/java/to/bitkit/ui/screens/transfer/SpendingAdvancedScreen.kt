package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.appViewModel
import to.bitkit.ui.blocktankViewModel
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.NumberPadActionButton
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.TransferAmount
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.useTransfer
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.TransferViewModel

@Composable
fun SpendingAdvancedScreen(
    viewModel: TransferViewModel,
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
    onOrderCreated: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val app = appViewModel ?: return
    val blocktank = blocktankViewModel ?: return
    val currencies = LocalCurrencies.current
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
                .imePadding()
        ) {

            var lspBalance by rememberSaveable { mutableLongStateOf(0) }
            var overrideSats: Long? by remember { mutableStateOf(null) }

            val clientBalance = order.clientBalanceSat
            var fee: Long? by remember { mutableStateOf(null) }
            var isLoading by remember { mutableStateOf(false) }

            val transferValues = useTransfer(clientBalance.toLong())

            val isValid = lspBalance >= transferValues.minLspBalance && lspBalance <= transferValues.maxLspBalance

            // Fetch LSP Fee estimate
            LaunchedEffect(lspBalance) {
                fee = null
                if (lspBalance < transferValues.minLspBalance) {
                    return@LaunchedEffect
                }
                val estimate = blocktank.estimateOrderFee(
                    spendingBalanceSats = clientBalance,
                    receivingBalanceSats = lspBalance.toULong(),
                )
                fee = estimate.feeSat.toLong()
            }

            Spacer(modifier = Modifier.height(32.dp))
            Display(text = stringResource(R.string.lightning__spending_advanced__title).withAccent(accentColor = Colors.Purple))
            Spacer(modifier = Modifier.height(32.dp))

            TransferAmount(
                primaryDisplay = currencies.primaryDisplay,
                overrideSats = overrideSats,
                onSatsChange = { sats ->
                    lspBalance = sats
                    overrideSats = null
                },
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.requiredHeight(20.dp),
            ) {
                Caption13Up(text = stringResource(R.string.lightning__spending_advanced__fee), color = Colors.White64)
                Spacer(modifier = Modifier.width(4.dp))
                fee?.let {
                    MoneySSB(it)
                } ?: run {
                    Caption13Up(text = "—", color = Colors.White64)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Actions Row
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                // Min Button
                NumberPadActionButton(
                    text = stringResource(R.string.common__min),
                    color = Colors.Purple,
                    onClick = {
                        overrideSats = transferValues.minLspBalance
                    },
                )
                // Default Button
                NumberPadActionButton(
                    text = stringResource(R.string.common__default),
                    color = Colors.Purple,
                    onClick = {
                        overrideSats = order.lspBalanceSat.toLong()
                    },
                )
                // Max Button
                NumberPadActionButton(
                    text = stringResource(R.string.common__max),
                    color = Colors.Purple,
                    onClick = {
                        overrideSats = transferValues.maxLspBalance
                    },
                )
            }
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = {
                    isLoading = true
                    scope.launch {
                        try {
                            val newOrder = blocktank.createOrder(
                                spendingBalanceSats = clientBalance,
                                receivingBalanceSats = lspBalance.toULong(),
                            )
                            viewModel.onAdvancedOrderCreated(newOrder)
                            onOrderCreated()
                        } catch (e: Throwable) {
                            app.toast(e)
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading && isValid,
                isLoading = isLoading,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
