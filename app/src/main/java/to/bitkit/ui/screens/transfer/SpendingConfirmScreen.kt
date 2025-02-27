package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
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
import to.bitkit.ui.appViewModel
import to.bitkit.ui.blocktankViewModel
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.MoneySSB
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
            val networkFee = order.networkFeeSat
            val serviceFeeSat = order.serviceFeeSat
            val totalFee = order.feeSat

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
                    amount = serviceFeeSat.toLong(),
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

            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PrimaryButton(
                    text = stringResource(R.string.common__learn_more),
                    size = ButtonSize.Small,
                    fullWidth = false,
                    onClick = onLearnMoreClick,
                )
                PrimaryButton(
                    text = stringResource(R.string.common__advanced),
                    size = ButtonSize.Small,
                    fullWidth = false,
                    onClick = onAdvancedClick,
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
                        viewModel.payOrder(order)
                        onConfirm()
                    }
                }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RowScope.FeeInfo(
    label: String,
    amount: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .weight(1f)
            .padding(top = 16.dp)
    ) {
        Caption13Up(
            text = label,
            color = Colors.White64,
        )
        Spacer(modifier = Modifier.height(8.dp))
        MoneySSB(sats = amount)
        Spacer(modifier = Modifier.weight(1f))
        HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
    }
}
