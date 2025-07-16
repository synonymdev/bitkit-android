package to.bitkit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.datetime.Clock
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.BalanceSource
import org.lightningdevkit.ldknode.BestBlock
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.LightningBalance
import org.lightningdevkit.ldknode.NodeStatus
import to.bitkit.R
import to.bitkit.ext.amountLong
import to.bitkit.ext.balanceTypeString
import to.bitkit.ext.channelIdString
import to.bitkit.ext.createChannelDetails
import to.bitkit.ext.formatted
import to.bitkit.models.LnPeer
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.Toast
import to.bitkit.models.formatToModernDisplay
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.ChannelStatusUi
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.LightningChannel
import to.bitkit.ui.components.Subtitle
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.rememberMoneyText
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsTextButtonRow
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.MainUiState
import java.time.Instant

@Composable
fun NodeInfoScreen(
    navController: NavController,
) {
    val wallet = walletViewModel?: return
    val app = appViewModel ?: return
    val settings = settingsViewModel ?: return
    val context = LocalContext.current

    val uiState by wallet.uiState.collectAsStateWithLifecycle()
    val isDevModeEnabled by settings.isDevModeEnabled.collectAsStateWithLifecycle()

    Content(
        uiState = uiState,
        isDevModeEnabled = isDevModeEnabled,
        onBack = { navController.popBackStack() },
        onClose = { navController.navigateToHome() },
        onRefresh = { wallet.onPullToRefresh() },
        onDisconnectPeer = { wallet.disconnectPeer(it) },
        onCopy = { text ->
            app.toast(
                type = Toast.ToastType.SUCCESS,
                title = context.getString(R.string.common__copied),
                description = text
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Content(
    uiState: MainUiState,
    isDevModeEnabled: Boolean,
    onBack: () -> Unit = {},
    onClose: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onDisconnectPeer: (LnPeer) -> Unit = {},
    onCopy: (String) -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__node_info),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClose) },
        )
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                NodeIdSection(
                    nodeId = uiState.nodeId,
                    onCopy = onCopy,
                )

                if (isDevModeEnabled) {
                    NodeStateSection(
                        nodeLifecycleState = uiState.nodeLifecycleState,
                        nodeStatus = uiState.nodeStatus,
                    )

                    uiState.balanceDetails?.let { balanceDetails ->
                        WalletBalancesSection(balanceDetails = balanceDetails)

                        if (balanceDetails.lightningBalances.isNotEmpty()) {
                            LightningBalancesSection(balances = balanceDetails.lightningBalances)
                        }
                    }

                    if (uiState.channels.isNotEmpty()) {
                        ChannelsSection(
                            channels = uiState.channels,
                            onCopy = onCopy,
                        )
                    }

                    if (uiState.peers.isNotEmpty()) {
                        PeersSection(
                            peers = uiState.peers,
                            onDisconnectPeer = onDisconnectPeer,
                            onCopy = onCopy,
                        )
                    }
                }
                VerticalSpacer(16.dp)
            }
        }
    }
}

@Composable
private fun NodeIdSection(
    nodeId: String,
    onCopy: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.lightning__node_id))
        Subtitle(
            text = nodeId,
            modifier = Modifier.clickableAlpha(onClick = copyToClipboard(nodeId) {
                onCopy(nodeId)
            })
        )
    }
}

@Composable
private fun NodeStateSection(
    nodeLifecycleState: NodeLifecycleState,
    nodeStatus: NodeStatus?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("Node State")
        SettingsTextButtonRow(
            title = "Node State:",
            value = nodeLifecycleState.uiText,
        )

        nodeStatus?.let { status ->
            SettingsTextButtonRow(
                title = "Ready:",
                value = if (status.isRunning) "✅" else "⏳",
            )
            SettingsTextButtonRow(
                title = "Lightning wallet sync time:",
                value = status.latestLightningWalletSyncTimestamp
                    ?.let { Instant.ofEpochSecond(it.toLong()).formatted() }
                    ?: "Never",
            )
            SettingsTextButtonRow(
                title = "Onchain wallet sync time:",
                value = status.latestOnchainWalletSyncTimestamp
                    ?.let { Instant.ofEpochSecond(it.toLong()).formatted() }
                    ?: "Never",
            )
            SettingsTextButtonRow(
                title = "Block height:",
                value = "${status.currentBestBlock.height}",
            )
        }
    }
}

@Composable
private fun WalletBalancesSection(balanceDetails: BalanceDetails) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("Wallet Balances")
        Column {
            SettingsTextButtonRow(
                title = "Total onchain:",
                value = "₿ ${balanceDetails.totalOnchainBalanceSats.formatToModernDisplay()}",
            )
            SettingsTextButtonRow(
                title = "Spendable onchain:",
                value = "₿ ${balanceDetails.spendableOnchainBalanceSats.formatToModernDisplay()}",
            )
            SettingsTextButtonRow(
                title = "Total anchor channels reserve:",
                value = "₿ ${balanceDetails.totalAnchorChannelsReserveSats.formatToModernDisplay()}",
            )
            SettingsTextButtonRow(
                title = "Total lightning:",
                value = "₿ ${balanceDetails.totalLightningBalanceSats.formatToModernDisplay()}",
            )
        }
    }
}

@Composable
private fun LightningBalancesSection(balances: List<LightningBalance>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("Lightning Balances")
        balances.forEach { balance ->
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                VerticalSpacer(16.dp)
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BodyM(
                        text = balance.balanceTypeString(),
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    HorizontalSpacer(4.dp)
                    rememberMoneyText(balance.amountLong())?.let { text ->
                        BodyM(text = text.withAccent(accentColor = Colors.White64), color = Colors.White64)
                    }
                }
                VerticalSpacer(4.dp)
                Caption(
                    balance.channelIdString(),
                    color = Colors.White64,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                VerticalSpacer(16.dp)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ChannelsSection(
    channels: List<ChannelDetails>,
    onCopy: (String) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("Channels")
        channels.forEach { channel ->
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(52.dp)
                ) {
                    BodyM(
                        text = channel.channelId,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.clickableAlpha(onClick = copyToClipboard(channel.channelId) {
                            onCopy(channel.channelId)
                        })
                    )
                }
                LightningChannel(
                    capacity = (channel.channelValueSats).toLong(),
                    localBalance = (channel.outboundCapacityMsat / 1000u).toLong(),
                    remoteBalance = (channel.inboundCapacityMsat / 1000u).toLong(),
                    status = if (channel.isChannelReady) ChannelStatusUi.OPEN else ChannelStatusUi.PENDING,
                )
                VerticalSpacer(8.dp)

                ChannelDetailRow(
                    title = "Ready:",
                    value = if (channel.isChannelReady) "✅" else "❌",
                )
                ChannelDetailRow(
                    title = "Usable:",
                    value = if (channel.isUsable) "✅" else "❌",
                )
                ChannelDetailRow(
                    title = "Announced:",
                    value = if (channel.isAnnounced) "🌐" else "🔒",
                )
                ChannelDetailRow(
                    title = "Inbound capacity:",
                    value = "₿ ${(channel.inboundCapacityMsat / 1000u).formatToModernDisplay()}",
                )
                ChannelDetailRow(
                    title = "Inbound htlc max:",
                    value = "₿ ${(channel.inboundHtlcMaximumMsat?.div(1000u) ?: 0u).formatToModernDisplay()}",
                )
                ChannelDetailRow(
                    title = "Inbound htlc min:",
                    value = "₿ ${(channel.inboundHtlcMinimumMsat / 1000u).formatToModernDisplay()}",
                )
                ChannelDetailRow(
                    title = "Next outbound htlc limit:",
                    value = "₿ ${(channel.nextOutboundHtlcLimitMsat / 1000u).formatToModernDisplay()}",
                )
                ChannelDetailRow(
                    title = "Next outbound htlc min:",
                    value = "₿ ${(channel.nextOutboundHtlcMinimumMsat / 1000u).formatToModernDisplay()}",
                )
                ChannelDetailRow(
                    title = "Confirmations:",
                    value = "${channel.confirmations ?: 0}/${channel.confirmationsRequired ?: 0}",
                )

                VerticalSpacer(16.dp)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PeersSection(
    peers: List<LnPeer>,
    onDisconnectPeer: (LnPeer) -> Unit,
    onCopy: (String) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("Peers")
        peers.forEach { peer ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(52.dp)
            ) {
                BodyM(
                    text = peer.toString(),
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickableAlpha(onClick = copyToClipboard(peer.toString()) {
                            onCopy(peer.toString())
                        })
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .clickableAlpha(onClick = { onDisconnectPeer(peer) })
                ) {
                    Icon(
                        imageVector = Icons.Default.RemoveCircleOutline,
                        contentDescription = stringResource(R.string.common__close),
                        tint = Colors.Red,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ChannelDetailRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
    ) {
        Caption(text = title)
        Caption(text = value, color = Colors.White64)
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            isDevModeEnabled = false,
            uiState = MainUiState(
                nodeId = "0348a2b7c2d3f4e5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9",
            ),
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewDevMode() {
    AppThemeSurface {
        val syncTime = Clock.System.now().epochSeconds.toULong()
        Content(
            isDevModeEnabled = true,
            uiState = MainUiState(
                nodeLifecycleState = NodeLifecycleState.Running,
                nodeStatus = NodeStatus(
                    isRunning = true,
                    isListening = true,
                    currentBestBlock = BestBlock(
                        height = 1000u,
                        blockHash = "0123456789abcDef",
                    ),
                    latestLightningWalletSyncTimestamp = syncTime,
                    latestOnchainWalletSyncTimestamp = syncTime,
                    latestFeeRateCacheUpdateTimestamp = null,
                    latestRgsSnapshotTimestamp = null,
                    latestNodeAnnouncementBroadcastTimestamp = null,
                    latestChannelMonitorArchivalHeight = null,
                ),
                nodeId = "0348a2b7c2d3f4e5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9",
                peers = listOf(
                    LnPeer(
                        nodeId = "0248a2b7c2d3f4e5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9",
                        address = "192.168.1.1:9735",
                    ),
                ),
                channels = listOf(
                    createChannelDetails().copy(
                        channelId = "abc123def456789012345678901234567890123456789012345678901234567890",
                        channelValueSats = 1000000UL,
                        outboundCapacityMsat = 400000000UL,
                        inboundCapacityMsat = 600000000UL,
                        confirmationsRequired = 6U,
                        confirmations = 6U,
                        isChannelReady = true,
                        isUsable = true,
                        isAnnounced = true,
                        nextOutboundHtlcLimitMsat = 400000000UL,
                        nextOutboundHtlcMinimumMsat = 1000UL,
                        inboundHtlcMinimumMsat = 1000UL,
                        inboundHtlcMaximumMsat = 600000000UL,
                    ),
                    createChannelDetails().copy(
                        channelId = "def456789012345678901234567890123456789012345678901234567890abc123",
                        channelValueSats = 500000UL,
                        outboundCapacityMsat = 300000000UL,
                        inboundCapacityMsat = 200000000UL,
                        confirmationsRequired = 6U,
                        confirmations = 3U,
                        nextOutboundHtlcLimitMsat = 300000000UL,
                        nextOutboundHtlcMinimumMsat = 1000UL,
                        inboundHtlcMinimumMsat = 1000UL,
                        inboundHtlcMaximumMsat = 200000000UL,
                    ),
                ),
                balanceDetails = BalanceDetails(
                    totalOnchainBalanceSats = 1000000UL,
                    spendableOnchainBalanceSats = 900000UL,
                    totalAnchorChannelsReserveSats = 50000UL,
                    totalLightningBalanceSats = 500000UL,
                    lightningBalances = listOf(
                        LightningBalance.ClaimableOnChannelClose(
                            channelId = "abc123def456789012345678901234567890123456789012345678901234567890",
                            counterpartyNodeId = "0248a2b7c2d3f4e5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9",
                            amountSatoshis = 250000UL,
                            transactionFeeSatoshis = 1000UL,
                            outboundPaymentHtlcRoundedMsat = 0UL,
                            outboundForwardedHtlcRoundedMsat = 0UL,
                            inboundClaimingHtlcRoundedMsat = 0UL,
                            inboundHtlcRoundedMsat = 0UL,
                        ),
                        LightningBalance.ClaimableAwaitingConfirmations(
                            channelId = "def456789012345678901234567890123456789012345678901234567890abc123",
                            counterpartyNodeId = "0348a2b7c2d3f4e5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9",
                            amountSatoshis = 150000UL,
                            confirmationHeight = 850005U,
                            source = BalanceSource.COUNTERPARTY_FORCE_CLOSED,
                        ),
                        LightningBalance.MaybeTimeoutClaimableHtlc(
                            channelId = "789012345678901234567890123456789012345678901234567890abc123def456",
                            counterpartyNodeId = "0448a2b7c2d3f4e5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9",
                            amountSatoshis = 100000UL,
                            claimableHeight = 850010U,
                            paymentHash = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
                            outboundPayment = true,
                        ),
                    ),
                    pendingBalancesFromChannelClosures = listOf(),
                ),
            ),
        )
    }
}
