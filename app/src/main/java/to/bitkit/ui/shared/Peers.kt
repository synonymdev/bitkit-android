package to.bitkit.ui.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.lightningdevkit.ldknode.PeerDetails
import to.bitkit.R

@Composable
internal fun Peers(
    peers: SnapshotStateList<PeerDetails>,
    onToggle: (PeerDetails) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row {
            Text(
                text = stringResource(R.string.peers),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = peers.filter { it.isConnected }.size.toString(),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Column {
            peers.sortedBy { it.isConnected }.forEachIndexed { i, it ->
                if (i > 0 && peers.size > 1) {
                    HorizontalDivider()
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TogglePeerIcon(it.isConnected) { onToggle(it) }
                    Text(
                        text = it.nodeId,
                        style = MaterialTheme.typography.labelSmall,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun TogglePeerIcon(
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val (icon, color) = Pair(
        if (isActive) Icons.Default.Cloud else Icons.Default.CloudOff,
        if (isActive) colorScheme.primary else colorScheme.error,
    )
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .border(BorderStroke(1.2.dp, colorScheme.onBackground.copy(alpha = .2f)), MaterialTheme.shapes.medium)
            .size(28.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(R.string.status),
            tint = color,
            modifier = Modifier.size(16.dp),
        )
    }
}
