package to.bitkit.ui

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.LightningBalance
import to.bitkit.R
import to.bitkit.ext.ellipsisMiddle
import to.bitkit.ext.formatted
import to.bitkit.models.LnPeer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.InfoField
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.WalletViewModel
import java.text.NumberFormat
import java.time.Instant

@Composable
fun NodeStateScreen(
    viewModel: WalletViewModel,
    navController: NavController,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ScreenColumn {
        AppTopBar(
            stringResource(R.string.settings__adv__lightning_node),
            onBackClick = { navController.popBackStack() },
            actions = {
                IconButton(viewModel::refreshState) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Sync",
                    )
                }
            })
        Column(
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row {
                        Text(
                            text = "Node State:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = uiState.nodeLifecycleState.displayState,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    uiState.nodeStatus?.let {
                        Row {
                            Text(
                                text = "Ready:",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = if (it.isRunning) "✅" else "⏳",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Row {
                            Text(
                                text = "Last lightning wallet sync time:",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            val lastSyncTime = it.latestLightningWalletSyncTimestamp
                                ?.let { Instant.ofEpochSecond(it.toLong()).formatted() }
                                ?: "Never"
                            Text(
                                text = lastSyncTime,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Row {
                            Text(
                                text = "Last onchain wallet sync time:",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            val lastSyncTime = it.latestOnchainWalletSyncTimestamp
                                ?.let { Instant.ofEpochSecond(it.toLong()).formatted() }
                                ?: "Never"
                            Text(
                                text = lastSyncTime,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Row {
                            Text(
                                text = "Block height:",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "${it.currentBestBlock.height}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            InfoField(
                value = uiState.nodeId,
                label = stringResource(R.string.lightning__node_id),
                maxLength = 44,
                trailingIcon = { CopyToClipboardButton(uiState.nodeId) },
            )
            Peers(uiState.peers, viewModel::disconnectPeer)
            Channels(
                channels = uiState.channels,
                hasPeers = uiState.peers.isNotEmpty(),
                onChannelOpenTap = viewModel::openChannel,
                onChannelCloseTap = viewModel::closeChannel,
            )
            uiState.balanceDetails?.let {
                Balances(it)
            }
            Spacer(modifier = Modifier.height(1.dp))
        }
    }
}

@Composable
private fun Balances(
    balanceDetails: BalanceDetails,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Wallet Balances",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(12.dp),
        )
        HorizontalDivider()
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            Row {
                Text(
                    text = "Total onchain:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = moneyString(balanceDetails.totalOnchainBalanceSats.toLong()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row {
                Text(
                    text = "Spendable onchain:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = moneyString(balanceDetails.spendableOnchainBalanceSats.toLong()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row {
                Text(
                    text = "Total anchor channels reserve:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = moneyString(balanceDetails.totalAnchorChannelsReserveSats.toLong()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row {
                Text(
                    text = "Total lightning:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = moneyString(balanceDetails.totalLightningBalanceSats.toLong()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Lightning Balances",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(12.dp),
        )
        HorizontalDivider()
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            balanceDetails.lightningBalances.forEach {
                LightningBalanceRow(it)
            }
        }
    }
}

@Composable
private fun LightningBalanceRow(balance: LightningBalance) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = balance.balanceTypeString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = balance.channelIdString(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
        )
        Text(
            text = moneyString(balance.amountLong()),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun LightningBalance.balanceTypeString(): String {
    return when (this) {
        is LightningBalance.ClaimableOnChannelClose -> "Claimable on Channel Close"
        is LightningBalance.ClaimableAwaitingConfirmations -> "Claimable Awaiting Confirmations (Height: $confirmationHeight)"
        is LightningBalance.ContentiousClaimable -> "Contentious Claimable"
        is LightningBalance.MaybeTimeoutClaimableHtlc -> "Maybe Timeout Claimable HTLC"
        is LightningBalance.MaybePreimageClaimableHtlc -> "Maybe Preimage Claimable HTLC"
        is LightningBalance.CounterpartyRevokedOutputClaimable -> "Counterparty Revoked Output Claimable"
    }
}

private fun LightningBalance.amountLong(): Long {
    return when (this) {
        is LightningBalance.ClaimableOnChannelClose -> this.amountSatoshis.toLong()
        is LightningBalance.ClaimableAwaitingConfirmations -> this.amountSatoshis.toLong()
        is LightningBalance.ContentiousClaimable -> this.amountSatoshis.toLong()
        is LightningBalance.MaybeTimeoutClaimableHtlc -> this.amountSatoshis.toLong()
        is LightningBalance.MaybePreimageClaimableHtlc -> this.amountSatoshis.toLong()
        is LightningBalance.CounterpartyRevokedOutputClaimable -> this.amountSatoshis.toLong()
    }
}

private fun LightningBalance.channelIdString(): String {
    return when (this) {
        is LightningBalance.ClaimableOnChannelClose -> this.channelId
        is LightningBalance.ClaimableAwaitingConfirmations -> this.channelId
        is LightningBalance.ContentiousClaimable -> this.channelId
        is LightningBalance.MaybeTimeoutClaimableHtlc -> this.channelId
        is LightningBalance.MaybePreimageClaimableHtlc -> this.channelId
        is LightningBalance.CounterpartyRevokedOutputClaimable -> this.channelId
    }
}

@Composable
private fun Peers(
    peers: List<LnPeer>,
    onDisconnect: (LnPeer) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Peers",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${peers.size}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        HorizontalDivider()
        peers.forEach {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Companion.CenterVertically,
                modifier = Modifier.padding(16.dp)
            ) {
                Box(
                    modifier = Modifier.Companion
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color = Colors.Green)
                )
                Text(
                    text = "${it.nodeId.ellipsisMiddle(25)}@${it.address}",
                    style = MaterialTheme.typography.labelSmall,
                    overflow = TextOverflow.Companion.Ellipsis,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                BoxButton(
                    onClick = { onDisconnect(it) },
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.RemoveCircleOutline,
                        contentDescription = stringResource(R.string.common__close),
                        tint = Colors.Red,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun Channels(
    channels: List<ChannelDetails>,
    hasPeers: Boolean,
    onChannelOpenTap: () -> Unit,
    onChannelCloseTap: (ChannelDetails) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Channels",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${channels.size}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        HorizontalDivider()
        channels.forEach {
            Column(modifier = Modifier.padding(16.dp)) {
                ChannelItemUi(
                    channel = it,
                    onClose = { onChannelCloseTap(it) },
                )
            }
            HorizontalDivider()
        }
        TextButton(
            onClick = onChannelOpenTap,
            enabled = hasPeers,
            shape = RectangleShape,
        ) {
            Text("Open channel to trusted peer", modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ChannelItemUi(
    channel: ChannelDetails,
    onClose: () -> Unit,
) {
    val outbound = (channel.outboundCapacityMsat / 1000u).toLong()
    val inbound = (channel.inboundCapacityMsat / 1000u).toLong()

    val isUsable = channel.isUsable
    val isAnnounced = channel.isAnnounced

    val inboundHtlcMax = (channel.inboundHtlcMaximumMsat?.div(1000u) ?: 0u).toLong()
    val inboundHtlcMin = (channel.inboundHtlcMinimumMsat / 1000u).toLong()
    val nextOutboundHtlcLimit = (channel.nextOutboundHtlcLimitMsat / 1000u).toLong()
    val nextOutboundHtlcMin = (channel.nextOutboundHtlcMinimumMsat / 1000u).toLong()

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = channel.channelId.ellipsisMiddle(48),
                style = MaterialTheme.typography.labelSmall,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            BoxButton(
                onClick = onClose,
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.RemoveCircleOutline,
                    contentDescription = null,
                    tint = Colors.Red,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        LinearProgressIndicator(
            color = if (channel.isChannelReady) Colors.Purple else Colors.Gray5,
            trackColor = Colors.Gray5,
            progress = (inbound.toDouble() / (outbound + inbound))::toFloat,
            modifier = Modifier
                .height(8.dp)
                .fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(moneyString(outbound), style = MaterialTheme.typography.labelSmall)
            Text(moneyString(inbound), style = MaterialTheme.typography.labelSmall)
        }
        Column {
            val style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal)

            Text("Ready: ${if (channel.isChannelReady) "✅" else "❌"}", style = style)
            Text("Usable: ${if (isUsable) "✅" else "❌"}", style = style)
            Text("Announced: $isAnnounced", style = style)
            Text("Inbound htlc max: " + moneyString(inboundHtlcMax), style = style)
            Text("Inbound htlc min: " + moneyString(inboundHtlcMin), style = style)
            Text("Next outbound htlc limit: " + moneyString(nextOutboundHtlcLimit), style = style)
            Text("Next outbound htlc min: " + moneyString(nextOutboundHtlcMin), style = style)
            Text("Confirmations: ${channel.confirmations ?: 0u}/${channel.confirmationsRequired ?: 0u}", style = style)
        }
    }
}

@Composable
private fun BoxButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clickable(onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            content()
        }
    }
}

@Composable
private fun CopyToClipboardButton(text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.app_name)
    IconButton(
        onClick = {
            scope.launch {
                val clipData = ClipData.newPlainText(label, text)
                clipboard.setClipEntry(ClipEntry(clipData))
            }
        }
    ) {
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = null,
            modifier = Modifier.Companion.size(16.dp),
        )
    }
}

@Composable
private fun moneyString(
    value: Long?,
    currency: String? = "sat",
): AnnotatedString {
    if (value == null) return AnnotatedString("")
    val locale = java.util.Locale.GERMANY
    return buildAnnotatedString {
        append(NumberFormat.getNumberInstance(locale).format(value))
        currency?.let {
            append(" ")
            withStyle(SpanStyle(color = colorScheme.onBackground.copy(0.5f))) {
                append(currency)
            }
        }
    }
}
