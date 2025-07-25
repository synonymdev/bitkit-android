package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.appViewModel
import to.bitkit.ui.blocktankViewModel
import to.bitkit.ui.components.AmountInput
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.NumberPadActionButton
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.TransferViewModel
import kotlin.math.max
import kotlin.math.min
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
    val resources = LocalContext.current.resources
    val transferValues by viewModel.transferValues.collectAsStateWithLifecycle()

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__transfer__nav_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onCloseClick) },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .imePadding()
        ) {
            var satsAmount by rememberSaveable { mutableLongStateOf(0) }
            var overrideSats: Long? by remember { mutableStateOf(null) }
            var isLoading by remember { mutableStateOf(false) }

            val availableAmount = LocalBalances.current.totalOnchainSats - 512u // default tx fee
            var maxLspFee by remember { mutableStateOf(0uL) }

            val feeMaximum = max(0, availableAmount.toLong() - maxLspFee.toLong())
            val maximum = min(transferValues.maxClientBalance.toLong(), feeMaximum) //TODO USE MAX AVAILABLE TO TRANSFER INSTEAD OF MAX ONCHAIN BALANCE

            // Update maxClientBalance Effect
            LaunchedEffect(satsAmount) {
                viewModel.updateTransferValues(satsAmount.toULong())
                Logger.debug(
                    "satsAmount changed - maxClientBalance: ${transferValues.maxClientBalance}",
                    context = "SpendingAmountScreen"
                )
            }

            // Update maxLspFee Effect
            LaunchedEffect(availableAmount, transferValues.maxLspBalance) { //TODO MOVE TO VIEWMODEL
                runCatching {
                    val estimate = blocktank.estimateOrderFee(
                        spendingBalanceSats = availableAmount,
                        receivingBalanceSats = transferValues.maxLspBalance,
                    )
                    maxLspFee = estimate.feeSat
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            Display(text = stringResource(R.string.lightning__spending_amount__title).withAccent(accentColor = Colors.Purple))
            Spacer(modifier = Modifier.height(32.dp))

            AmountInput(
                primaryDisplay = currencies.primaryDisplay,
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
                        val quarter = (availableAmount.toDouble() / 4.0).roundToLong()
                        val amount = min(quarter, maximum)
                        overrideSats = amount
                    },
                )
                // Max Button
                NumberPadActionButton(
                    text = stringResource(R.string.common__max),
                    color = Colors.Purple,
                    onClick = {
                        overrideSats = maximum
                    },
                )
            }
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = {
                    if (transferValues.maxLspBalance == 0uL) {
                        app.toast(
                            type = Toast.ToastType.ERROR,
                            title = resources.getString(R.string.lightning__spending_amount__error_max__title),
                            description = resources.getString(R.string.lightning__spending_amount__error_max__description_zero),
                        )
                        return@PrimaryButton
                    }

                    isLoading = true
                    scope.launch {
                        try {
                            val order = blocktank.createOrder(satsAmount.toULong())
                            viewModel.onOrderCreated(order)
                            onOrderCreated()
                        } catch (e: Throwable) {
                            app.toast(e)
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading && satsAmount != 0L,
                isLoading = isLoading,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
