package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.services.filterOpen
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.MoneyDisplay
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppSwitchDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.transferViewModel
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.walletViewModel

@Composable
fun SavingsAdvancedScreen(
    onContinueClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    val currency = currencyViewModel ?: return
    val wallet = walletViewModel ?: return
    val transfer = transferViewModel ?: return

    val walletState by wallet.uiState.collectAsStateWithLifecycle()
    val openChannels = walletState.channels.filterOpen()

    var selectedChannelIds by remember { mutableStateOf(setOf<String>()) }

    // Select all open channels on init
    LaunchedEffect(Unit) {
        selectedChannelIds = openChannels.map { it.channelId }.toSet()
    }

    fun toggleChannel(channelId: String) {
        selectedChannelIds = if (channelId in selectedChannelIds) {
            selectedChannelIds - channelId
        } else {
            selectedChannelIds + channelId
        }
    }

    val channelItems = remember(openChannels, selectedChannelIds) {
        openChannels.map {
            TransferChannelUiState(
                channelId = it.channelId,
                balance = wallet.getChannelAmountOnClose(it.channelId),
                isSelected = selectedChannelIds.contains(it.channelId),
            )
        }
    }

    SavingsAdvancedContent(
        channelItems = channelItems,
        onChannelItemClick = { channelId -> toggleChannel(channelId) },
        onAmountClick = { currency.togglePrimaryDisplay() },
        onContinueClick = {
            transfer.setSelectedChannelIds(
                selectedChannelIds.takeUnless { it.size == openChannels.size } ?: emptySet()
            )
            onContinueClick()
        },
        onBackClick = onBackClick,
        onCloseClick = onCloseClick,
    )
}

@Composable
private fun SavingsAdvancedContent(
    channelItems: List<TransferChannelUiState>,
    onChannelItemClick: (String) -> Unit = {},
    onAmountClick: () -> Unit = {},
    onContinueClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    val totalAmount = channelItems.filter { it.isSelected }.sumOf { it.balance }

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
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            Display(text = stringResource(R.string.lightning__savings_advanced__title).withAccent())
            Spacer(modifier = Modifier.height(8.dp))
            BodyM(
                text = stringResource(R.string.lightning__savings_advanced__text),
                color = Colors.White64,
            )
            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(
                    items = channelItems.sortedBy { it.channelId },
                    key = { _, channel -> channel.channelId },
                ) { index, channel ->
                    ChannelItem(
                        channelName = "${stringResource(R.string.lightning__connection)} ${index + 1}",
                        balanceSat = channel.balance,
                        iSelected = channel.isSelected,
                        onClick = { onChannelItemClick(channel.channelId) },
                    )
                }
            }

            Caption13Up(text = stringResource(R.string.lightning__savings_advanced__total), color = Colors.White64)
            Spacer(modifier = Modifier.height(8.dp))
            MoneyDisplay(sats = totalAmount.toLong(), onClick = onAmountClick)
            Spacer(modifier = Modifier.height(32.dp))

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onContinueClick,
                enabled = channelItems.any { it.isSelected },
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun ChannelItem(
    channelName: String,
    balanceSat: ULong,
    iSelected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
        ) {
            Column {
                Caption13Up(
                    text = channelName,
                    color = Colors.White64,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                MoneySSB(sats = balanceSat.toLong())
            }
            Switch(
                checked = iSelected,
                colors = AppSwitchDefaults.colors,
                onCheckedChange = null, // handled by parent
            )
        }
        HorizontalDivider(color = Colors.White10)
    }
}

private data class TransferChannelUiState(
    val channelId: String,
    val balance: ULong,
    val isSelected: Boolean,
)

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun SavingsAdvancedScreenPreview() {
    AppThemeSurface {
        SavingsAdvancedContent(
            channelItems = listOf(
                TransferChannelUiState(
                    channelId = "channelId_1",
                    balance = 45_000u,
                    isSelected = true,
                ),
                TransferChannelUiState(
                    channelId = "channelId_2",
                    balance = 55_000u,
                    isSelected = true,
                ),
                TransferChannelUiState(
                    channelId = "channelId_3",
                    balance = 50_000u,
                    isSelected = false,
                ),
            ),
        )
    }
}
