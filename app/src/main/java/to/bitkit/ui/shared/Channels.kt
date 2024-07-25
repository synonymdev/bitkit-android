package to.bitkit.ui.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.R

@Composable
internal fun Channels(
    channels: SnapshotStateList<ChannelDetails>,
    onChannelClose: (ChannelDetails) -> Unit,
) {
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
        }

        channels.forEach {
            Card(
                elevation = CardDefaults.cardElevation(2.5.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val outbound by remember(it) {
                        mutableStateOf(it.outboundCapacityMsat / 1000u)
                    }
                    val inbound by remember(it) {
                        mutableStateOf(it.inboundCapacityMsat / 1000u)
                    }

                    ChannelItem(
                        isUsable = it.isUsable,
                        channelId = it.channelId,
                        outbound = outbound.toString(),
                        inbound = inbound.toString(),
                        onClose = { onChannelClose(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelItem(
    isUsable: Boolean,
    channelId: String,
    outbound: String,
    inbound: String,
    onClose: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = channelId,
            style = MaterialTheme.typography.labelSmall,
        )
        Card(
            colors = CardDefaults.cardColors(colorScheme.background),
            modifier = Modifier.fillMaxWidth(),
        ) {
            LinearProgressIndicator(
                color = if (isUsable) colorScheme.secondary else colorScheme.error,
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
            Icon(
                imageVector = if (isUsable) Icons.Default.Cloud else Icons.Default.CloudOff,
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
