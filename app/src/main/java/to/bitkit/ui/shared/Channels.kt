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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.R

@Composable
internal fun Channels(
    channels: List<ChannelDetails>,
    onChannelClose: (ChannelDetails) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row {
            Text(
                text = stringResource(R.string.channels),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${channels.size}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        channels.forEach {
            OutlinedCard(
                elevation = CardDefaults.cardElevation(.5.dp),
                colors = CardDefaults.outlinedCardColors(colorScheme.background),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val outbound by remember(it) { mutableStateOf(it.outboundCapacityMsat / 1000u) }
                    val inbound by remember(it) { mutableStateOf(it.inboundCapacityMsat / 1000u) }

                    ChannelItem(
                        isReady = it.isUsable,
                        channelId = it.channelId,
                        outbound = outbound.toInt(),
                        inbound = inbound.toInt(),
                        onClose = { onChannelClose(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelItem(
    isReady: Boolean,
    channelId: String,
    outbound: Int,
    inbound: Int,
    onClose: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = channelId,
            style = MaterialTheme.typography.labelSmall,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        Card {
            LinearProgressIndicator(
                color = if (isReady) colorScheme.primary else colorScheme.error,
                trackColor = colorScheme.surfaceVariant,
                progress = (inbound.toDouble() / (outbound + inbound))::toFloat,
                modifier = Modifier
                    .height(8.dp)
                    .fillMaxWidth(),
            )
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(moneyString("$outbound"), style = MaterialTheme.typography.labelSmall)
            Text(moneyString("$inbound"), style = MaterialTheme.typography.labelSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = if (isReady) "🟢 Ready" else "⏳ Pending", style = MaterialTheme.typography.labelLarge)
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
