package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.ui.appViewModel
import to.bitkit.ui.blocktankViewModel
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.FullWidthTextButton
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.TransferViewModel

@Composable
fun SpendingConfirmScreen(
    viewModel: TransferViewModel,
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    val app = appViewModel ?: return
    val blocktank = blocktankViewModel ?: return
    val scope = rememberCoroutineScope()
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

        ) {
            val clientBalance = order.clientBalanceSat
            val lspBalance = order.lspBalanceSat
            val lspFee = order.feeSat - clientBalance

            Spacer(modifier = Modifier.height(32.dp))
            Display(text = stringResource(R.string.lightning__transfer__confirm).withAccent(accentColor = Colors.Purple))
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.height(IntrinsicSize.Min)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(top = 16.dp)
                ) {
                    Caption13Up(
                        text = stringResource(R.string.lightning__spending_confirm__network_fee),
                        color = Colors.White64,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BodySSB(text = "Todo ₿ transactionFee")
                    Spacer(modifier = Modifier.weight(1f))
                    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                }
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(top = 16.dp)
                ) {
                    Caption13Up(
                        text = stringResource(R.string.lightning__spending_confirm__lsp_fee),
                        color = Colors.White64,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BodySSB(text = "₿ $lspFee")
                    Spacer(modifier = Modifier.weight(1f))
                    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.height(IntrinsicSize.Min)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(top = 16.dp)
                ) {
                    Caption13Up(
                        text = stringResource(R.string.lightning__spending_confirm__amount),
                        color = Colors.White64,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BodySSB(text = "₿ $clientBalance")
                    Spacer(modifier = Modifier.weight(1f))
                    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                }
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(top = 16.dp)
                ) {
                    Caption13Up(
                        text = stringResource(R.string.lightning__spending_confirm__total),
                        color = Colors.White64,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BodySSB(text = "Todo ₿ totalFee")
                    Spacer(modifier = Modifier.weight(1f))
                    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PrimaryButton(
                    text = stringResource(R.string.common__learn_more),
                    size = ButtonSize.Small,
                    fullWidth = false,
                    onClick = {
                        // TODO: nav to Liquidity
                    },
                )
                PrimaryButton(
                    text = stringResource(R.string.common__advanced),
                    size = ButtonSize.Small,
                    fullWidth = false,
                    onClick = {
                        // TODO: nav to Advanced
                    },
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            Image(
                painter = painterResource(id = R.drawable.coin_stack_x),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(256.dp)
                    .align(alignment = CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(24.dp))
            if (state.txId == null) {
                Spacer(modifier = Modifier.weight(1f))
                var isPaying by remember { mutableStateOf(false) }
                PrimaryButton(
                    text = "Confirm",
                    onClick = {
                        isPaying = true
                        viewModel.payOrder(state.order!!)
                    },
                    enabled = !isPaying,
                )
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
                        scope.launch {
                            try {
                                blocktank.open(orderId = order.id)
                                Logger.info("Channel opened for order ${order.id}")
                                app.toast(Toast.ToastType.SUCCESS, "Success", "Manual open success")
                            } catch (e: Throwable) {
                                Logger.error("Error opening channel for order ${order.id}", e)
                                app.toast(e)
                            }
                        }
                    },
                ) {
                    Text(text = "Try manual open")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
