package to.bitkit.ui.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.R
import to.bitkit.ext.ellipsisMiddle
import to.bitkit.ui.theme.AppThemeSurface

@Composable
internal fun Channels(
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
                val outbound by remember(it) { mutableStateOf(it.outboundCapacityMsat / 1000u) }
                val inboundHtlcMax by remember(it) { mutableStateOf(it.inboundHtlcMaximumMsat?.div(1000u) ?: 0u) }
                val inboundHtlcMin by remember(it) { mutableStateOf(it.inboundHtlcMinimumMsat / 1000u) }
                val nextOutboundHtlcLimit by remember(it) { mutableStateOf(it.nextOutboundHtlcLimitMsat / 1000u) }
                val nextOutboundHtlcMin by remember(it) { mutableStateOf(it.nextOutboundHtlcMinimumMsat / 1000u) }
                val inbound by remember(it) { mutableStateOf(it.inboundCapacityMsat / 1000u) }

                ChannelItem(
                    isReady = it.isChannelReady,
                    isUsable = it.isUsable,
                    isAnnounced = it.isAnnounced,
                    inboundHtlcMax = inboundHtlcMax.toLong(),
                    inboundHtlcMin = inboundHtlcMin.toLong(),
                    nextOutboundHtlcLimit = nextOutboundHtlcLimit.toLong(),
                    nextOutboundHtlcMin = nextOutboundHtlcMin.toLong(),
                    channelId = it.channelId,
                    outbound = outbound.toLong(),
                    inbound = inbound.toLong(),
                    confirmationsText = "Confirmations: ${it.confirmations ?: 0u}/${it.confirmationsRequired ?: 0u}",
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
private fun ChannelItem(
    isReady: Boolean,
    isUsable: Boolean,
    isAnnounced: Boolean,
    inboundHtlcMax: Long,
    inboundHtlcMin: Long,
    nextOutboundHtlcLimit: Long,
    nextOutboundHtlcMin: Long,
    channelId: String,
    outbound: Long,
    inbound: Long,
    confirmationsText: String,
    onClose: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = channelId.ellipsisMiddle(50),
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
            Text(moneyString(outbound), style = MaterialTheme.typography.labelSmall)
            Text(moneyString(inbound), style = MaterialTheme.typography.labelSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = if (isReady) "✅ Ready" else "⏳ Pending", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.weight(1f))
            BoxButton(
                onClick = onClose,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.large)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = stringResource(R.string.close),
                    color = colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Column {
            val style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal)
            Text("Usable: ${if (isUsable) "✅" else "❌"}", style = style)
            Text("Announced: $isAnnounced", style = style)
            Text("Inbound htlc max: " + moneyString(inboundHtlcMax), style = style)
            Text("Inbound htlc min: " + moneyString(inboundHtlcMin), style = style)
            Text("Next outbound htlc limit: " + moneyString(nextOutboundHtlcLimit), style = style)
            Text("Next outbound htlc min: " + moneyString(nextOutboundHtlcMin), style = style)
            Text(confirmationsText, style = style)
        }
    }
}

@Suppress("SpellCheckingInspection")
@Preview(showBackground = true)
@Composable
private fun ChannelItemPreview() {
    AppThemeSurface {
        ChannelItem(
            isReady = true,
            isUsable = true,
            channelId = "abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz0123456789",
            outbound = 1000,
            inbound = 1000,
            onClose = {},
            isAnnounced = false,
            inboundHtlcMax = 123L,
            inboundHtlcMin = 246L,
            nextOutboundHtlcLimit = 531L,
            nextOutboundHtlcMin = 762L,
            confirmationsText = "Confirmations: 1/2",
        )
    }
}
