package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.appViewModel
import to.bitkit.ui.blocktankViewModel
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.NumberPadActionButton
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.TransferAmount
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.TransferViewModel
import kotlin.math.roundToLong

@Composable
fun SpendingAmountScreen(
    viewModel: TransferViewModel,
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
    onOrderCreated: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val app = appViewModel ?: return
    val blocktank = blocktankViewModel ?: return
    val currencies = LocalCurrencies.current

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
            var spendingBalanceSats by rememberSaveable { mutableLongStateOf(0) }
            var overrideSats: Long? by remember { mutableStateOf(null) }
            var isLoading by remember { mutableStateOf(false) }

            val balances = LocalBalances.current
            // TODO Calculate the maximum amount that can be transferred
            val transactionFee = 512u // TODO calc transaction.fee
            val availableAmount = balances.totalOnchainSats - transactionFee

            Spacer(modifier = Modifier.height(32.dp))
            Display(text = stringResource(R.string.lightning__spending_amount__title).withAccent(accentColor = Colors.Purple))
            Spacer(modifier = Modifier.height(32.dp))

            TransferAmount(
                primaryDisplay = currencies.primaryDisplay,
                overrideSats = overrideSats,
                onSatsChange = { sats ->
                    spendingBalanceSats = sats
                    overrideSats = null
                },
            )

            Spacer(modifier = Modifier.weight(1f))

            // Actions
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Column {
                    Text13Up(
                        text = stringResource(R.string.wallet__send_available),
                        color = Colors.White64,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    MoneySSB(sats = availableAmount.toLong())
                }
                Spacer(modifier = Modifier.weight(1f))
                UnitButton(color = Colors.Purple)
                // 25% Button
                NumberPadActionButton(
                    text = stringResource(R.string.lightning__spending_amount__quarter),
                    color = Colors.Purple,
                    onClick = {
                        overrideSats = (availableAmount.toDouble() / 4.0).roundToLong()
                    },
                )
                // Max Button
                NumberPadActionButton(
                    text = stringResource(R.string.common__max),
                    color = Colors.Purple,
                    onClick = {
                        // TODO calculate actual max, use estimate fees?
                        overrideSats = (availableAmount.toDouble() * 0.9).roundToLong()
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
                            val order = blocktank.createOrder(spendingBalanceSats.toULong())
                            viewModel.onOrderCreated(order)
                            onOrderCreated()
                        } catch (e: Throwable) {
                            app.toast(e)
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading && spendingBalanceSats != 0L,
                isLoading = isLoading,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
