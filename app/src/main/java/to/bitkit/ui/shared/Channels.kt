package to.bitkit.ui.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.R
import to.bitkit.ext.ellipsisMiddle
import to.bitkit.ui.theme.Colors

@Composable
fun Channels(
    channels: List<ChannelDetails>,
    hasPeers: Boolean,
    onChannelOpenTap: () -> Unit,
    onChannelCloseTap: (ChannelDetails) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp)) {
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
        FullWidthTextButton(
            onClick = { onChannelOpenTap() },
            enabled = hasPeers
        ) { Text("Open channel to trusted peer") }
    }
}

@Composable
private fun ChannelItemUi(
    channel: ChannelDetails,
    onClose: () -> Unit,
) {
    val outbound = (channel.outboundCapacityMsat / 1000u).toLong()
    val inbound = (channel.inboundCapacityMsat / 1000u).toLong()

    val isReady = channel.isChannelReady
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
                    contentDescription = stringResource(R.string.close),
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
