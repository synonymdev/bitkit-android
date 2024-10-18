package to.bitkit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.lightningdevkit.ldknode.BalanceDetails
import to.bitkit.ext.formatted
import to.bitkit.ui.shared.Channels
import to.bitkit.ui.shared.Peers
import to.bitkit.ui.shared.moneyString
import java.time.Instant

@Composable
fun NodeStateScreen(viewModel: WalletViewModel) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle()
    val contentState = uiState.value.asContent() ?: return

    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Node State",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
        )
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
                        text = contentState.nodeLifecycleState.name,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                contentState.nodeStatus?.let {
                    Row {
                        Text(
                            text = "Ready:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = if (it.isRunning) "Yes" else "No",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row {
                        Text(
                            text = "Last sync time:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        val lastSyncTime = it.latestWalletSyncTimestamp
                            ?.let { Instant.ofEpochSecond(it.toLong()).formatted() }
                            ?: "Never"
                        Text(
                            text = lastSyncTime,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        Peers(contentState.peers, viewModel::disconnectPeer)
        Channels(
            contentState.channels,
            contentState.peers.isNotEmpty(),
            viewModel::openChannel,
            viewModel::closeChannel,
        )
        contentState.balanceDetails?.let {
            BalanceDetails(it)
        }
    }
}

@Composable
private fun BalanceDetails(
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
            // TODO: balanceDetails.lightningBalances
        }
    }
}
