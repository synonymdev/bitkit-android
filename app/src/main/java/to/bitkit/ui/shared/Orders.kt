package to.bitkit.ui.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.bitkit.models.blocktank.BtOrder
import to.bitkit.models.blocktank.BtOrderState2

@Composable
internal fun Orders(
    orders: List<BtOrder>,
    onPayTap: (order: BtOrder) -> Unit,
    onManualOpenTap: (order: BtOrder) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Orders",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${orders.size}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        orders.forEach {
            HorizontalDivider()
            Column {
                Text(
                    text = it.id,
                    style = MaterialTheme.typography.titleSmall,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    modifier = Modifier.padding(12.dp),
                )
                when (it.state2) {
                    BtOrderState2.created -> FullWidthTextButton(onClick = { onPayTap(it).let {  } }) { Text(" Pay") }
                    BtOrderState2.paid -> {
                        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("✅Payment sent", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "You can close the app now. We will notify you when the channel is ready.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    else -> Unit
                }
                FullWidthTextButton(onClick = { onManualOpenTap(it) }) { Text("Try manual open") }
            }
        }
    }
}
