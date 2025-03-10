package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.R
import to.bitkit.services.filterOpen
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.MoneyDisplay
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.transferViewModel
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.walletViewModel

@Composable
fun SavingsConfirmScreen(
    onAdvancedClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    val currency = currencyViewModel ?: return
    val transfer = transferViewModel ?: return
    val wallet = walletViewModel ?: return

    val walletState by wallet.uiState.collectAsStateWithLifecycle()
    val openChannels = walletState.channels.filterOpen()

    val hasMultiple = openChannels.size > 1

    val selectedChannelIds by transfer.selectedChannelIdsState.collectAsStateWithLifecycle()
    val selectedChannels: List<ChannelDetails>? = selectedChannelIds
        .takeIf { it.isNotEmpty() }
        ?.let { openChannels.filter { channel -> it.contains(channel.channelId) } }

    val hasSelected = selectedChannelIds.isNotEmpty()

    val channels = selectedChannels ?: openChannels

    val amount = channels.sumOf { channel ->
        wallet.getChannelAmountOnClose(channel.channelId)
    }

    fun onConfirm() {
        /* TODO: onConfirm */
    }

    SavingsConfirmContent(
        amount = amount,
        hasMultiple = hasMultiple,
        hasSelected = hasSelected,
        onBackClick = onBackClick,
        onCloseClick = onCloseClick,
        onAmountClick = { currency.togglePrimaryDisplay() },
        onAdvancedClick = onAdvancedClick,
        onSelectAllClick = { transfer.setSelectedChannelIds(emptySet()) },
        onConfirm = { onConfirm() },
    )
}

@Composable
private fun SavingsConfirmContent(
    amount: ULong,
    hasMultiple: Boolean,
    hasSelected: Boolean,
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
    onAmountClick: () -> Unit = {},
    onAdvancedClick: () -> Unit = {},
    onSelectAllClick: () -> Unit = {},
    onConfirm: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
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
            Spacer(modifier = Modifier.height(32.dp))
            Display(text = stringResource(R.string.lightning__transfer__confirm).withAccent())
            Spacer(modifier = Modifier.height(32.dp))

            Caption13Up(text = stringResource(R.string.lightning__savings_confirm__label), color = Colors.White64)
            Spacer(modifier = Modifier.height(8.dp))
            MoneyDisplay(sats = amount.toLong(), onClick = onAmountClick)

            if (hasMultiple) {
                Spacer(modifier = Modifier.height(24.dp))
                if (hasSelected) {
                    PrimaryButton(
                        text = stringResource(R.string.lightning__savings_confirm__transfer_all),
                        size = ButtonSize.Small,
                        fullWidth = false,
                        onClick = { onSelectAllClick() },
                    )
                } else {
                    PrimaryButton(
                        text = stringResource(R.string.common__advanced),
                        size = ButtonSize.Small,
                        fullWidth = false,
                        onClick = { onAdvancedClick() },
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Image(
                painter = painterResource(R.drawable.piggybank),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(256.dp)
                    .graphicsLayer(scaleX = -1f)
                    .align(alignment = CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(32.dp))

            var isLoading by remember { mutableStateOf(false) }
            SwipeToConfirm(
                text = stringResource(R.string.lightning__transfer__swipe),
                loading = isLoading,
                color = Colors.Brand,
                onConfirm = {
                    scope.launch {
                        isLoading = true
                        delay(300)
                        onConfirm()
                    }
                }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun SavingsConfirmScreenPreview() {
    AppThemeSurface {
        SavingsConfirmContent(
            amount = 50_123u,
            hasMultiple = true,
            hasSelected = false,
        )
    }
}
