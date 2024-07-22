package to.bitkit.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.bdk.Channel
import to.bitkit.ext.toHex
import to.bitkit.ui.MainViewModel
import to.bitkit.ui.PEER
import to.bitkit.ui.ldkLocalBalance

@Composable
fun ChannelsScreen(
    viewModel: MainViewModel,
) {
    Spacer(modifier = Modifier.size(48.dp))
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    Channel.open(PEER)
                    viewModel.sync()
                },
                enabled = viewModel.peers.isNotEmpty()
            ) {
                Text("Open Channel")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Channels",
                    style = MaterialTheme.typography.titleMedium,
                )
                ConnectPeerIcon(viewModel.peers, viewModel::togglePeerConnection)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${ldkLocalBalance()} sats",
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            viewModel.channels.forEach {
                val isUsable = it._is_usable
                val channelId = it._channel_id.toHex()
                val outbound = it._outbound_capacity_msat / 1000
                val inbound = it._inbound_capacity_msat / 1000
                Card(
                    elevation = CardDefaults.cardElevation(2.5.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        ChannelItem(
                            isActive = isUsable,
                            channelId = channelId,
                            outbound = outbound.toString(),
                            inbound = inbound.toString(),
                            onClose = {
                                Channel.close(channelId, PEER)
                                viewModel.sync()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectPeerIcon(
    peers: List<String>,
    onClick: () -> Unit,
) {
    val icon = if (peers.isEmpty()) Icons.Default.LinkOff else Icons.Default.Link
    val color = if (peers.isEmpty()) colorScheme.error else colorScheme.secondary
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .border(BorderStroke(1.5.dp, color), RoundedCornerShape(16.dp))
            .size(28.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
fun ChannelItem(
    isActive: Boolean,
    channelId: String,
    outbound: String,
    inbound: String,
    onClose: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val color = if (isActive) colorScheme.secondary else colorScheme.error
        Text(
            text = channelId,
            style = MaterialTheme.typography.labelSmall,
        )
        Card(
            colors = CardDefaults.cardColors(colorScheme.background),
            modifier = Modifier.fillMaxWidth(),
        ) {
            LinearProgressIndicator(
                color = color,
                trackColor = Color.Transparent,
                progress = {
                    (inbound.toDouble() / (outbound.toDouble() + inbound.toDouble())).toFloat()
                },
                modifier = Modifier.height(8.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "$outbound sats", style = MaterialTheme.typography.labelSmall)
            Text(text = "$inbound sats", style = MaterialTheme.typography.labelSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val icon = if (isActive) Icons.Default.Cloud else Icons.Default.CloudOff
            Icon(
                imageVector = icon,
                contentDescription = stringResource(R.string.status),
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = onClose,
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(text = stringResource(R.string.close))
            }
        }
    }
}
