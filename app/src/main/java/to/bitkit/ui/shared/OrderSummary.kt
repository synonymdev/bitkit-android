package to.bitkit.ui.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import to.bitkit.models.blocktank.BtOrder
import to.bitkit.ui.shared.util.onLongPress

@Composable
fun OrderSummary(order: BtOrder) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(12.dp)
    ) {
        val clipboardManager = LocalClipboardManager.current
        Text(
            text = order.id,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .onLongPress { clipboardManager.setText(AnnotatedString(order.id)) }
                .padding(bottom = 8.dp)
        )
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
}
