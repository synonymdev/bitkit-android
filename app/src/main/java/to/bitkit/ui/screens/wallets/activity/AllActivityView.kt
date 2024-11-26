package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.R
import to.bitkit.ext.amountSats
import to.bitkit.ext.toActivityItemDate
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.navigateToActivityItem
import to.bitkit.ui.navigateToAllActivity
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
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
            .clickable(onClick = { navController?.navigateToActivityItem(item.id) })
            .padding(horizontal = 0.dp, vertical = 16.dp)
    ) {
        PaymentStatusIcon(item)
        Spacer(modifier = Modifier.width(12.dp))
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
        Column {
            Text(
                text = displayText,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = item.latestUpdateTimestamp.toActivityItemDate(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        val symbol = when (item.direction) {
            PaymentDirection.OUTBOUND -> "-"
            PaymentDirection.INBOUND -> "+"
        }
        item.amountSats?.let { sats ->
            Text("$symbol " + moneyString(sats.toLong()))
        }
    }
}

@Composable
fun PaymentStatusIcon(item: PaymentDetails) {
    when {
        item.status == PaymentStatus.FAILED -> {
            IconInCircle(
                icon = Icons.Default.Close,
                tint = Color.Red,
            )
        }

        else -> {
            val icon = when (item.direction) {
                PaymentDirection.OUTBOUND -> Icons.Default.ArrowUpward
                else -> Icons.Default.ArrowDownward
            }
            val color = if (item.kind == PaymentKind.Onchain) Orange500 else Purple500
            IconInCircle(
                icon = icon,
                tint = color,
            )
        }
    }
}

@Composable
fun IconInCircle(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(32.dp)
            .background(color = tint.copy(alpha = 0.16f), shape = CircleShape)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
    }
}

enum class ActivityType {
    ALL, LIGHTNING, ONCHAIN
}

@Composable
fun ActivityLatest(
    type: ActivityType,
    walletViewModel: WalletViewModel,
    navController: NavController,
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
                HorizontalDivider(color = DividerDefaults.color.copy(alpha = 0.25f))
            }
            item {
                if (items.isEmpty()) {
                    Text("No activity", Modifier.padding(16.dp))
                } else {
                    TextButton(
                        onClick = { navController?.navigateToAllActivity() },
                        modifier = Modifier.padding(8.dp)
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
    viewModel: WalletViewModel,
    navController: NavController,
) {
    ScreenColumn {
        AppTopBar(navController, stringResource(R.string.all_activity))
        val items = viewModel.activityItems.value
        AllActivityView(items, navController)
    }
}

@Composable
private fun AllActivityView(
    items: List<PaymentDetails>?,
    navController: NavController? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        if (items != null) {
            LazyColumn {
                items(items = items, key = { it.id }) { item ->
                    ActivityRow(item, navController)
                    HorizontalDivider(color = DividerDefaults.color.copy(alpha = 0.25f))
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
        id = "1",
        kind = PaymentKind.Onchain,
        amountMsat = 1234_000u,
        direction = PaymentDirection.INBOUND,
        status = PaymentStatus.FAILED,
        latestUpdateTimestamp = 1711110966_UL
    ),
    PaymentDetails(
        id = "2",
        kind = PaymentKind.Onchain,
        amountMsat = 23_456_000u,
        direction = PaymentDirection.OUTBOUND,
        status = PaymentStatus.PENDING,
        latestUpdateTimestamp = 1725810966_UL
    ),
    PaymentDetails(
        id = "3",
        kind = PaymentKind.Bolt11("bolt11", null, null),
        amountMsat = 541_000u,
        direction = PaymentDirection.INBOUND,
        status = PaymentStatus.SUCCEEDED,
        latestUpdateTimestamp = 1729510966_UL
    ),
    PaymentDetails(
        id = "4",
        kind = PaymentKind.Onchain,
        amountMsat = 826_000u,
        direction = PaymentDirection.OUTBOUND,
        status = PaymentStatus.SUCCEEDED,
        latestUpdateTimestamp = 1713510966_UL
    ),
    PaymentDetails(
        id = "5",
        kind = PaymentKind.Bolt11("bolt11", null, null),
        amountMsat = 42_000u,
        direction = PaymentDirection.OUTBOUND,
        status = PaymentStatus.FAILED,
        latestUpdateTimestamp = 1716510966_UL
    ),
    PaymentDetails(
        id = "6",
        kind = PaymentKind.Bolt11("bolt11", null, null),
        amountMsat = 107_000u,
        direction = PaymentDirection.OUTBOUND,
        status = PaymentStatus.SUCCEEDED,
        latestUpdateTimestamp = 1717510966_UL
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
