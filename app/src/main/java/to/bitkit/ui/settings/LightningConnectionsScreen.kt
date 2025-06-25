package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.R
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.mockChannelDetails
import to.bitkit.models.formatToModernDisplay
import to.bitkit.ui.blocktankViewModel
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.ChannelStatusUi
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.LightningChannel
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Title
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.navigateToTransferFunding
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.walletViewModel
import to.bitkit.viewmodels.filterPaid

@Composable
fun LightningConnectionsScreen(
    navController: NavController,
    viewModel: LightningConnectionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.initState()
    }

    Content(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onClickAddConnection = { navController.navigateToTransferFunding() },
        onClickExportLogs = {
            // TODO: zip & share logs
        },
        onClickChannel = { channel ->
            // TODO: Navigate to channel details
        },
    )
}

@Composable
private fun Content(
    uiState: LightningConnectionsUiState,
    onBack: () -> Unit = {},
    onClickAddConnection: () -> Unit = {},
    onClickExportLogs: () -> Unit = {},
    onClickChannel: (ChannelDetails) -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__connections),
            onBackClick = onBack,
            actions = {
                IconButton(onClick = onClickAddConnection) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.lightning__conn_button_add),
                    )
                }
            }
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            VerticalSpacer(16.dp)
            LightningBalancesSection(uiState.localBalance, uiState.remoteBalance)
            VerticalSpacer(32.dp)

            Caption13Up(stringResource(R.string.lightning__conn_open), modifier = Modifier.padding(top = 16.dp))
            ChannelList(
                status = ChannelStatusUi.OPEN,
                channels = uiState.openChannels,
                onClickChannel = onClickChannel,
            )
            FillHeight()

            // Bottom Section
            VerticalSpacer(16.dp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SecondaryButton(
                    text = stringResource(R.string.lightning__conn_button_export_logs),
                    onClick = onClickExportLogs,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = stringResource(R.string.lightning__conn_button_add),
                    onClick = onClickAddConnection,
                    modifier = Modifier.weight(1f)
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun LightningBalancesSection(localBalance: ULong, remoteBalance: ULong) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BalanceColumn(
            label = stringResource(R.string.lightning__spending_label),
            balance = localBalance,
            icon = Icons.Default.ArrowUpward,
            color = Colors.Purple,
        )
        BalanceColumn(
            label = stringResource(R.string.lightning__receiving_label),
            balance = remoteBalance,
            icon = Icons.Default.ArrowDownward,
            color = Colors.White,
        )
    }
}

@Composable
private fun BalanceColumn(label: String, balance: ULong, icon: ImageVector, color: Color) {
    Column {
        Caption13Up(text = label, color = Colors.White64)
        VerticalSpacer(8.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(22.dp)
            )
            Title(text = balance.toLong().formatToModernDisplay(), color = color)
        }
    }
}

@Composable
private fun ChannelList(
    channels: List<ChannelDetails>,
    status: ChannelStatusUi = ChannelStatusUi.OPEN,
    onClickChannel: (ChannelDetails) -> Unit,
) {
    channels.map { channel ->
        Channel(
            channel = channel,
            status = status,
            onClick = { onClickChannel(channel) }
        )
    }
}

@Composable
private fun Channel(
    channel: ChannelDetails,
    status: ChannelStatusUi,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickableAlpha { onClick() }
            .padding(vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BodyMSB(
                text = getChannelName(channel),
                color = if (status == ChannelStatusUi.CLOSED) Colors.White64 else Colors.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = Colors.White64,
                modifier = Modifier.size(24.dp)
            )
        }
        VerticalSpacer(8.dp)
        LightningChannel(
            capacity = channel.channelValueSats.toLong(),
            localBalance = channel.amountOnClose.toLong(),
            remoteBalance = (channel.inboundCapacityMsat / 1000u).toLong(),
            status = status,
            showLabels = false
        )
        HorizontalDivider(
            color = Colors.White10,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}

@Composable
private fun getChannelName(channel: ChannelDetails): String {
    val default = channel.inboundScidAlias?.toString() ?: "${channel.channelId.take(10)}…"
    val blocktank = blocktankViewModel ?: return default
    val paidBlocktankOrders = blocktank.orders.filterPaid()
    val wallet = walletViewModel ?: return default
    val mainUiState by wallet.uiState.collectAsStateWithLifecycle()
    val channels = mainUiState.channels

    // TODO: sort channels to make it deterministic, because node.listChannels returns a list in random order
    val pendingChannels = paidBlocktankOrders.filter { order ->
        // orders without a corresponding known channel are considered pending
        !channels.any { c -> c.fundingTxo?.txid == order.channel?.fundingTx?.id }
    }
    val pendingIndex = pendingChannels.indexOfFirst { order -> channel.channelId == order.id }
    val channelIndex = channels.indexOfFirst { c -> channel.channelId == c.channelId }

    val connectionText = stringResource(R.string.lightning__connection)

    return when {
        channelIndex == -1 -> {
            if (pendingIndex == -1) {
                default
            } else {
                val index = channels.size + pendingIndex
                "$connectionText ${index + 1}"
            }
        }

        else -> "$connectionText ${channelIndex + 1}"
    }
}

@Suppress("SpellCheckingInspection")
@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = LightningConnectionsUiState(
                localBalance = 50_000u,
                remoteBalance = 450_000u,
                isNodeRunning = true,
                openChannels = listOf(
                    mockChannelDetails().copy(
                        channelId = "channel_1",
                        counterpartyNodeId = "03abcd1234567890abcd1234567890abcd1234567890abcd1234567890abcd1234",
                        channelValueSats = 1_000_000u,
                        outboundCapacityMsat = 300_000_000u,
                        inboundCapacityMsat = 700_000_000u,
                        isChannelReady = true,
                        inboundScidAlias = null,
                    ),
                )
            )
        )
    }
}
