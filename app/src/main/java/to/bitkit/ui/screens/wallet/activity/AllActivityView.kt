package to.bitkit.ui.screens.wallet.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.ext.amountSats
import to.bitkit.ui.ActivityItemRoute
import to.bitkit.ui.Routes
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.shared.moneyString
import to.bitkit.ui.theme.Orange500
import to.bitkit.ui.theme.Purple500

@Composable
fun ActivityRow(
    item: PaymentDetails,
    navController: NavController? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { navController?.navigate(ActivityItemRoute(item.id)) })
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        PaymentStatusIcon(item)
        Spacer(modifier = Modifier.width(4.dp))
        val displayText = when {
            item.direction == PaymentDirection.OUTBOUND -> when (item.status) {
                PaymentStatus.FAILED -> "Sending Failed"
                PaymentStatus.PENDING -> "Sending..."
                PaymentStatus.SUCCEEDED -> "Sent"
            }

            else -> when (item.status) {
                PaymentStatus.FAILED -> "Receive Failed"
                PaymentStatus.PENDING -> "Receiving..."
                PaymentStatus.SUCCEEDED -> "Received"
            }
        }
        Text(text = displayText)
        Spacer(modifier = Modifier.weight(1f))
        item.amountSats?.let { sats ->
            Text(moneyString(sats.toLong()))
        }
    }
}

@Composable
fun PaymentStatusIcon(item: PaymentDetails) {
    when {
        item.status == PaymentStatus.FAILED -> {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = Color.Red
            )
        }

        else -> {
            val icon = when (item.direction) {
                PaymentDirection.OUTBOUND -> Icons.Default.ArrowUpward
                else -> Icons.Default.ArrowDownward
            }
            val color = if (item.kind == PaymentKind.Onchain) Orange500 else Purple500
            Icon(
                imageVector = icon,
                contentDescription = "Payment Icon",
                tint = color
            )
        }
    }
}

enum class ActivityType {
    ALL, LIGHTNING, ONCHAIN
}

@Composable
fun ActivityLatest(
    type: ActivityType,
    navController: NavController,
    walletViewModel: WalletViewModel = hiltViewModel(),
) {
    when (type) {
        ActivityType.ALL -> ActivityList(walletViewModel.latestActivityItems.value, navController)
        ActivityType.LIGHTNING -> ActivityList(walletViewModel.latestLightningActivityItems.value, navController)
        ActivityType.ONCHAIN -> ActivityList(walletViewModel.latestOnchainActivityItems.value, navController)
    }
}

@Composable
fun ActivityList(
    items: List<PaymentDetails>?,
    navController: NavController? = null,
) {
    if (items != null) {
        LazyColumn(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            items(items = items, key = { it.id }) { item ->
                ActivityRow(item, navController)
                if (item != items.last()) {
                    HorizontalDivider()
                }
            }
            item {
                if (items.isEmpty()) {
                    Text("No activity", Modifier.padding(16.dp))
                } else {
                    TextButton(
                        onClick = { navController?.navigate(Routes.AllActivity.destination) },
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text("Show All Activity")
                    }
                }
            }
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("No activity available.", Modifier.padding(16.dp))
        }
    }
}

@Composable
fun AllActivityScreen(
    walletViewModel: WalletViewModel = hiltViewModel(),
    navController: NavController,
) {
    val items = walletViewModel.activityItems.value
    AllActivityView(items, navController)
}

@Composable
private fun AllActivityView(
    items: List<PaymentDetails>?,
    navController: NavController? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            text = "All Activity",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(16.dp)
        )
        if (items != null) {
            LazyColumn {
                items(items = items, key = { it.id }) { item ->
                    ActivityRow(item, navController)
                    if (item != items.last()) {
                        HorizontalDivider()
                    }
                }
            }
        } else {
            Text("No activity", Modifier.padding(16.dp))
        }
    }
}

// region preview
@Preview(showBackground = true)
@Composable
fun PreviewAllActivityView() {
    val sampleItems = PaymentDetailsPreviewProvider().values.toList()
    AllActivityView(items = sampleItems)
}

@Preview(showBackground = true)
@Composable
fun PreviewActivityListItems() {
    val sampleItems = PaymentDetailsPreviewProvider().values.toList()
    ActivityList(items = sampleItems)
}

@Preview(showBackground = true)
@Composable
fun PreviewActivityListEmpty() {
    ActivityList(items = emptyList())
}

@Preview(showBackground = true)
@Composable
fun PreviewActivityListNull() {
    ActivityList(items = null)
}

val testActivityItems: Sequence<PaymentDetails> = sequenceOf(
    PaymentDetails(
        id = "id1",
        kind = PaymentKind.Onchain,
        amountMsat = 1234_000u,
        direction = PaymentDirection.INBOUND,
        status = PaymentStatus.FAILED,
        latestUpdateTimestamp = 1729510966_UL
    ),
    PaymentDetails(
        id = "id2",
        kind = PaymentKind.Onchain,
        amountMsat = 23_456_000u,
        direction = PaymentDirection.OUTBOUND,
        status = PaymentStatus.PENDING,
        latestUpdateTimestamp = 1729510966_UL
    ),
    PaymentDetails(
        id = "id3",
        kind = PaymentKind.Bolt11("bolt11", null, null),
        amountMsat = 541_000u,
        direction = PaymentDirection.INBOUND,
        status = PaymentStatus.SUCCEEDED,
        latestUpdateTimestamp = 1729510966_UL
    ),
)

private class PaymentDetailsPreviewProvider : PreviewParameterProvider<PaymentDetails> {
    override val values: Sequence<PaymentDetails>
        get() = testActivityItems
}

@Preview(showBackground = true)
@Composable
private fun ActivityRowPreview(@PreviewParameter(PaymentDetailsPreviewProvider::class) item: PaymentDetails) {
    ActivityRow(item)
}
// endregion
