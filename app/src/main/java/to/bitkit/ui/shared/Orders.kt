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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ext.first
import to.bitkit.models.blocktank.BtOrder
import to.bitkit.models.blocktank.BtOrderState2
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.shared.util.onLongPress

@Composable
internal fun Orders(
    orders: List<BtOrder>,
    viewModel: WalletViewModel,
) {
    val activeOrders = orders.filter { it.state2 == BtOrderState2.created || it.state2 == BtOrderState2.paid }
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
            if (activeOrders.isNotEmpty()) {
                BoxButton(
                    onClick = viewModel::debugBtOrdersSync,
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
                text = "${activeOrders.size}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        activeOrders.forEachIndexed { index, order ->
            HorizontalDivider()
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    val clipboardManager = LocalClipboardManager.current
                    Text(
                        text = order.id,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .onLongPress { clipboardManager.setText(AnnotatedString(order.id)) }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = "Fees: " + moneyString(order.feeSat.toLong()),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Spending: " + moneyString(order.clientBalanceSat),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Receiving: " + moneyString(order.lspBalanceSat),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "State: ${order.state2}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when (val tx = order.payment.onchain.transactions.first) {
                null -> FullWidthTextButton(onClick = { viewModel.debugBtPayOrder(order) }) { Text(" Pay") }
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

            val isPaid = order.state2 == BtOrderState2.paid
            FullWidthTextButton(
                onClick = { viewModel.debugBtManualOpenChannel(order) },
                enabled = isPaid,
            ) { Text(text = "Try manual open${if (!isPaid) " (requires state=paid)" else ""}") }
        }
        var sats by remember { mutableIntStateOf(50_000) }
        OutlinedTextField(
            label = { Text("Sats") },
            value = "$sats",
            onValueChange = { sats = it.toIntOrNull() ?: 0 },
            textStyle = MaterialTheme.typography.labelSmall,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        FullWidthTextButton(
            onClick = { viewModel.debugBtCreateOrder(sats) }
        ) { Text("Create Order") }
    }
}
