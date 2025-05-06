package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.TransactionSpeed
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.ChannelStatusUi
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FeeInfo
import to.bitkit.ui.components.LightningChannel
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.TransferViewModel

@Composable
fun SpendingConfirmScreen(
    viewModel: TransferViewModel,
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
    onLearnMoreClick: () -> Unit = {},
    onAdvancedClick: () -> Unit = {},
    onConfirm: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val state by viewModel.spendingUiState.collectAsStateWithLifecycle()
    val order = state.order ?: return
    val isAdvanced = state.isAdvanced

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
                .verticalScroll(rememberScrollState())
        ) {
            val clientBalance = order.clientBalanceSat
            val networkFee = order.networkFeeSat
            val serviceFee = order.serviceFeeSat
            val totalFee = order.feeSat
            val lspBalance = order.lspBalanceSat

            Spacer(modifier = Modifier.height(32.dp))
            Display(text = stringResource(R.string.lightning__transfer__confirm).withAccent(accentColor = Colors.Purple))
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.height(IntrinsicSize.Min)
            ) {
                FeeInfo(
                    label = stringResource(R.string.lightning__spending_confirm__network_fee),
                    amount = networkFee.toLong(),
                )
                FeeInfo(
                    label = stringResource(R.string.lightning__spending_confirm__lsp_fee),
                    amount = serviceFee.toLong(),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.height(IntrinsicSize.Min)
            ) {
                FeeInfo(
                    label = stringResource(R.string.lightning__spending_confirm__amount),
                    amount = clientBalance.toLong(),
                )
                FeeInfo(
                    label = stringResource(R.string.lightning__spending_confirm__total),
                    amount = totalFee.toLong(),
                )
            }

            if (isAdvanced) {
                Spacer(modifier = Modifier.height(16.dp))
                LightningChannel(
                    capacity = (clientBalance + lspBalance).toLong(),
                    localBalance = clientBalance.toLong(),
                    remoteBalance = lspBalance.toLong(),
                    status = ChannelStatusUi.OPEN,
                    showLabels = true,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PrimaryButton(
                    text = stringResource(R.string.common__learn_more),
                    size = ButtonSize.Small,
                    fullWidth = false,
                    onClick = onLearnMoreClick,
                )
                PrimaryButton(
                    text = stringResource(
                        if (isAdvanced) R.string.lightning__spending_confirm__default else R.string.common__advanced
                    ),
                    size = ButtonSize.Small,
                    fullWidth = false,
                    onClick = {
                        if (isAdvanced) {
                            viewModel.onUseDefaultLspBalanceClick()
                        } else {
                            onAdvancedClick()
                        }
                    },
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (!isAdvanced) {
                Image(
                    painter = painterResource(id = R.drawable.coin_stack_x),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(256.dp)
                        .align(alignment = CenterHorizontally)
                )
            }
            Spacer(modifier = Modifier.weight(1f))

            var isLoading by remember { mutableStateOf(false) }
            SwipeToConfirm(
                text = stringResource(R.string.lightning__transfer__swipe),
                loading = isLoading,
                color = Colors.Purple,
                onConfirm = {
                    scope.launch {
                        isLoading = true
                        delay(300)
                        // TODO use TransactionSpeed from settings
                        viewModel.onTransferToSpendingConfirm(order, speed = TransactionSpeed.Fast)
                        onConfirm()
                    }
                }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
