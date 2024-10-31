package to.bitkit.ui.screens.wallet.activity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.ext.amountSats
import to.bitkit.ext.formatted
import to.bitkit.ui.Routes
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.scaffold.AppScaffold
import to.bitkit.ui.shared.moneyString
import to.bitkit.ui.theme.Green500
import to.bitkit.ui.theme.Orange500
import to.bitkit.ui.theme.Purple500
import java.time.Instant

@Composable
fun ActivityItemScreen(
    viewModel: WalletViewModel,
    navController: NavController,
    activityItem: Routes.ActivityItem,
) = AppScaffold(navController, viewModel, "Activity Detail") {
    val item = viewModel.activityItems.value?.find { it.id == activityItem.id } ?: return@AppScaffold
    ActivityItemView(item)
}

@Composable
fun ActivityItemView(
    item: PaymentDetails,
) {
    Column(
        modifier = Modifier.padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item.amountSats?.let { amountSats ->
                val text = (if (item.direction == PaymentDirection.OUTBOUND) "-" else "+") +
                    moneyString(amountSats.toLong())
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            val icon = when (item.kind) {
                PaymentKind.Onchain -> Icons.Default.Link
                else -> Icons.Default.Bolt
            }
            val color = if (item.kind == PaymentKind.Onchain) Orange500 else Purple500
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = color
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            val (statusText, statusIcon, statusColor) = when (item.status) {
                PaymentStatus.PENDING -> Triple("Pending", Icons.Default.AccessTime, Color.Gray)
                PaymentStatus.SUCCEEDED -> Triple("Confirmed", Icons.Default.Check, Green500)
                PaymentStatus.FAILED -> Triple("Failed", Icons.Default.Close, Color.Red)
            }

            Icon(
                imageVector = statusIcon,
                contentDescription = null,
                tint = statusColor,
            )
            Text(
                text = statusText,
                color = statusColor,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(text = "Date")
        Text(
            text = Instant.ofEpochSecond(item.latestUpdateTimestamp.toLong()).formatted(),
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewActivityItemView() {
    MaterialTheme {
        val item = PaymentDetails(
            id = "id1",
            amountMsat = 134_432_000u,
            direction = PaymentDirection.INBOUND,
            kind = PaymentKind.Onchain,
            status = PaymentStatus.SUCCEEDED,
            latestUpdateTimestamp = System.currentTimeMillis().toULong() / 1000u,
        )
        ActivityItemView(item = item)
    }
}
