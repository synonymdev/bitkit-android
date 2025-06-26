package to.bitkit.ui.settings

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import to.bitkit.ext.createChannelDetails
import to.bitkit.models.Toast
import to.bitkit.models.formatToModernDisplay
import to.bitkit.ui.Routes
import to.bitkit.ui.appViewModel
import to.bitkit.ui.blocktankViewModel
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.ChannelStatusUi
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.LightningChannel
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.SyncNodeView
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.Title
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.navigateToTransferFunding
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.walletViewModel

@Composable
fun LightningConnectionsScreen(
    navController: NavController,
    viewModel: LightningConnectionsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val blocktank = blocktankViewModel ?: return
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val app = appViewModel ?: return

    LaunchedEffect(blocktank.orders) {
        viewModel.setBlocktankOrders(blocktank.orders)
        viewModel.syncState()
    }

    Content(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onClickAddConnection = { navController.navigateToTransferFunding() },
        onClickExportLogs = {
            viewModel.zipAndShareLogs(
                onReady = { uri ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(intent, context.getString(R.string.lightning__export_logs))
                    )
                },
                onError = {
                    app.toast(
                        type = Toast.ToastType.WARNING,
                        title = context.getString(R.string.lightning__error_logs),
                        description = context.getString(R.string.lightning__error_logs_description),
                    )
                }
            )
        },
        onClickChannel = { channel ->
            navController.navigate(Routes.ChannelDetail(channel.channelId))
        },
        onRefresh = { viewModel.onPullToRefresh() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Content(
    uiState: LightningConnectionsUiState,
    onBack: () -> Unit = {},
    onClickAddConnection: () -> Unit = {},
    onClickExportLogs: () -> Unit = {},
    onClickChannel: (ChannelDetails) -> Unit = {},
    onRefresh: () -> Unit = {},
) {
    var showClosed by remember { mutableStateOf(false) }

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

        if (!uiState.isNodeRunning) {
            SyncNodeView(modifier = Modifier.fillMaxSize())
            return@ScreenColumn
        }

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                VerticalSpacer(16.dp)
                LightningBalancesSection(uiState.localBalance, uiState.remoteBalance)
                HorizontalDivider(modifier = Modifier.padding(top = 16.dp))

                // Pending Channels Section
                if (uiState.pendingConnections.isNotEmpty()) {
                    VerticalSpacer(16.dp)
                    Caption13Up(stringResource(R.string.lightning__conn_pending), color = Colors.White64)
                    ChannelList(
                        status = ChannelStatusUi.PENDING,
                        channels = uiState.pendingConnections.reversed(),
                        onClickChannel = onClickChannel,
                    )
                }

                // Open Channels Section
                if (uiState.openChannels.isNotEmpty()) {
                    VerticalSpacer(16.dp)
                    Caption13Up(stringResource(R.string.lightning__conn_open), color = Colors.White64)
                    ChannelList(
                        status = ChannelStatusUi.OPEN,
                        channels = uiState.openChannels.reversed(),
                        onClickChannel = onClickChannel,
                    )
                }

                // Closed & Failed Channels Section
                AnimatedVisibility(visible = showClosed && uiState.failedOrders.isNotEmpty()) {
                    Column {
                        VerticalSpacer(16.dp)
                        Caption13Up(stringResource(R.string.lightning__conn_failed), color = Colors.White64)
                        ChannelList(
                            status = ChannelStatusUi.CLOSED,
                            channels = uiState.failedOrders.reversed(),
                            onClickChannel = onClickChannel,
                        )
                    }
                }

                // Show/Hide Closed Channels Button
                if (uiState.failedOrders.isNotEmpty()) {
                    VerticalSpacer(16.dp)
                    TertiaryButton(
                        text = stringResource(
                            if (showClosed) R.string.lightning__conn_closed_hide else R.string.lightning__conn_closed_show
                        ),
                        onClick = { showClosed = !showClosed },
                        modifier = Modifier.wrapContentWidth()
                    )
                }

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
    ) {
        VerticalSpacer(16.dp)
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            BodyMSB(
                text = getChannelName(channel),
                color = if (status == ChannelStatusUi.CLOSED) Colors.White64 else Colors.White,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
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
        VerticalSpacer(16.dp)
        HorizontalDivider()
    }
}

@Composable
private fun getChannelName(channel: ChannelDetails): String {
    val default = channel.inboundScidAlias?.toString() ?: "${channel.channelId.take(10)}…"
    val blocktank = blocktankViewModel ?: return default
    val wallet = walletViewModel ?: return default
    val mainUiState by wallet.uiState.collectAsStateWithLifecycle()
    val channels = mainUiState.channels
    val paidOrders by blocktank.paidOrders.collectAsStateWithLifecycle()

    val paidBlocktankOrders = blocktank.orders.filter { order -> order.id in paidOrders.keys }

    // orders without a corresponding known channel are considered pending
    val pendingChannels = paidBlocktankOrders.filter { order ->
        channels.none { channel -> channel.fundingTxo?.txid == order.channel?.fundingTx?.id }
    }
    val pendingIndex = pendingChannels.indexOfFirst { order -> channel.channelId == order.id }

    // TODO: sort channels to get consistent index; node.listChannels returns a list in random order
    val channelIndex = channels.indexOfFirst { channel.channelId == it.channelId }

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

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = LightningConnectionsUiState(
                localBalance = 50_000u,
                remoteBalance = 450_000u,
                isNodeRunning = true,
                pendingConnections = listOf(
                    createChannelDetails().copy(
                        channelId = "order_1",
                        channelValueSats = 500_000u,
                        outboundCapacityMsat = 100_000_000u,
                        inboundCapacityMsat = 400_000_000u,
                        isChannelReady = false,
                        isUsable = false,
                    ),
                    createChannelDetails().copy(
                        channelId = "pending_1",
                        channelValueSats = 300_000u,
                        outboundCapacityMsat = 200_000_000u,
                        inboundCapacityMsat = 100_000_000u,
                        isChannelReady = false,
                        isUsable = false,
                    ),
                ),
                openChannels = listOf(
                    createChannelDetails().copy(
                        channelId = "channel_1",
                        channelValueSats = 1_000_000u,
                        outboundCapacityMsat = 300_000_000u,
                        inboundCapacityMsat = 700_000_000u,
                    ),
                ),
                failedOrders = listOf(
                    createChannelDetails().copy(
                        channelId = "failed_order_1",
                        channelValueSats = 200_000u,
                        outboundCapacityMsat = 50_000_000u,
                        inboundCapacityMsat = 150_000_000u,
                        isChannelReady = false,
                        isUsable = false,
                    ),
                    createChannelDetails().copy(
                        channelId = "failed_order_2",
                        channelValueSats = 100_000u,
                        outboundCapacityMsat = 30_000_000u,
                        inboundCapacityMsat = 70_000_000u,
                        isChannelReady = false,
                        isUsable = false,
                    ),
                )
            )
        )
    }
}

@Preview
@Composable
private fun PreviewNodeNotRunning() {
    AppThemeSurface {
        Content(
            uiState = LightningConnectionsUiState()
        )
    }
}
