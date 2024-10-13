package to.bitkit.ui.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ext.first
import to.bitkit.models.blocktank.BtOrder
import to.bitkit.models.blocktank.BtOrderState2

@Composable
internal fun Orders(
    orders: List<BtOrder>,
    onSyncTap: () -> Unit,
    onCreateTap: (sats: Int) -> Unit,
    onPayTap: (order: BtOrder) -> Unit,
    onManualOpenTap: (order: BtOrder) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "Orders",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (orders.isNotEmpty()) {
                BoxButton(
                    onClick = { onSyncTap() },
                    modifier = Modifier.clip(CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = stringResource(R.string.sync),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${orders.size}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        orders.forEachIndexed { i, it ->
            HorizontalDivider()
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = "$i. ${it.id}",
                    style = MaterialTheme.typography.titleSmall,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    text = "Fees: " + moneyString("${it.feeSat}"),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Spending: " + moneyString("${it.clientBalanceSat}"),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Receiving: " + moneyString("${it.lspBalanceSat}"),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "State: ${it.state2}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when (val tx = it.payment.onchain.transactions.first) {
                null -> FullWidthTextButton(onClick = { onPayTap(it).let { } }) { Text(" Pay") }
                else -> {
                    Card(shape = RectangleShape) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Text("✅ Payment sent", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "TxId: ${tx.txId}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "You can close the app now. We will notify you when the channel is ready.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            val isPaid = it.state2 == BtOrderState2.paid
            FullWidthTextButton(
                onClick = { onManualOpenTap(it) },
                enabled = isPaid,
            ) { Text(text = "Try manual open${if (!isPaid) " (requires state=paid)" else ""}") }
        }
        HorizontalDivider()
        FullWidthTextButton(onClick = { onCreateTap(100_000) }) { Text("Create Order of 100_000 sats") }
    }
}
