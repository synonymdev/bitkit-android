package to.bitkit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.LightningBalance
import to.bitkit.R
import to.bitkit.ext.formatted
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.Channels
import to.bitkit.ui.shared.CopyToClipboardButton
import to.bitkit.ui.shared.InfoField
import to.bitkit.ui.shared.Peers
import to.bitkit.ui.shared.moneyString
import java.time.Instant

@Composable
fun NodeStateScreen(
    viewModel: WalletViewModel,
    navController: NavController,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ScreenColumn {
        AppTopBar(
            stringResource(R.string.node_state),
            onBackClick = { navController.popBackStack() },
            actions = {
                IconButton(viewModel::refreshState) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.sync),
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
                            text = uiState.nodeLifecycleState.name,
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
                label = stringResource(R.string.node_id),
                maxLength = 44,
                trailingIcon = { CopyToClipboardButton(uiState.nodeId) },
            )
            Peers(uiState.peers, viewModel::disconnectPeer)
            Channels(
                uiState.channels,
                uiState.peers.isNotEmpty(),
                viewModel::openChannel,
                viewModel::closeChannel,
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
fun LightningBalanceRow(balance: LightningBalance) {
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

fun LightningBalance.balanceTypeString(): String {
    return when (this) {
        is LightningBalance.ClaimableOnChannelClose -> "Claimable on Channel Close"
        is LightningBalance.ClaimableAwaitingConfirmations -> "Claimable Awaiting Confirmations (Height: $confirmationHeight)"
        is LightningBalance.ContentiousClaimable -> "Contentious Claimable"
        is LightningBalance.MaybeTimeoutClaimableHtlc -> "Maybe Timeout Claimable HTLC"
        is LightningBalance.MaybePreimageClaimableHtlc -> "Maybe Preimage Claimable HTLC"
        is LightningBalance.CounterpartyRevokedOutputClaimable -> "Counterparty Revoked Output Claimable"
    }
}

fun LightningBalance.amountLong(): Long {
    return when (this) {
        is LightningBalance.ClaimableOnChannelClose -> this.amountSatoshis.toLong()
        is LightningBalance.ClaimableAwaitingConfirmations -> this.amountSatoshis.toLong()
        is LightningBalance.ContentiousClaimable -> this.amountSatoshis.toLong()
        is LightningBalance.MaybeTimeoutClaimableHtlc -> this.amountSatoshis.toLong()
        is LightningBalance.MaybePreimageClaimableHtlc -> this.amountSatoshis.toLong()
        is LightningBalance.CounterpartyRevokedOutputClaimable -> this.amountSatoshis.toLong()
    }
}

fun LightningBalance.channelIdString(): String {
    return when (this) {
        is LightningBalance.ClaimableOnChannelClose -> this.channelId
        is LightningBalance.ClaimableAwaitingConfirmations -> this.channelId
        is LightningBalance.ContentiousClaimable -> this.channelId
        is LightningBalance.MaybeTimeoutClaimableHtlc -> this.channelId
        is LightningBalance.MaybePreimageClaimableHtlc -> this.channelId
        is LightningBalance.CounterpartyRevokedOutputClaimable -> this.channelId
    }
}
