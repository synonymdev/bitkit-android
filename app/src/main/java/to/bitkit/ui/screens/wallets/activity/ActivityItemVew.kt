package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.R
import to.bitkit.ext.amountSats
import to.bitkit.ext.toActivityItemDate
import to.bitkit.ui.Routes
import to.bitkit.viewmodels.WalletViewModel
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.LabelText
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.DarkModePreview
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Green500
import to.bitkit.ui.theme.Orange500
import to.bitkit.ui.theme.Purple500

@Composable
fun ActivityItemScreen(
    viewModel: WalletViewModel,
    activityItem: Routes.ActivityItem,
    onBackClick: () -> Unit,
) {
    // TODO update to use bitkit-core activities
//    val item = viewModel.activityItems.value?.find { it.id == activityItem.id } ?: return
//    ScreenColumn {
//        AppTopBar("Activity Details", onBackClick = onBackClick)
//        ActivityItemView(item)
//    }
}

@Composable
fun ActivityItemView(
    item: PaymentDetails,
) {
    val amountPrefix = if (item.direction == PaymentDirection.OUTBOUND) "-" else "+"
    Column(
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item.amountSats?.let { amountSats ->
                BalanceHeaderView(
                    sats = amountSats.toLong(),
                    prefix = amountPrefix,
                    showBitcoinSymbol = false,
                    modifier = Modifier.weight(1f),
                )
            }

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

        Spacer(modifier = Modifier.height(12.dp))
        LabelText(text = stringResource(R.string.label_status))
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (statusText, statusIcon, statusColor) = when (item.status) {
                PaymentStatus.PENDING -> Triple("Pending", Icons.Default.AccessTime, Color.Gray)
                PaymentStatus.SUCCEEDED -> Triple("Successful", Icons.Default.Check, Green500)
                PaymentStatus.FAILED -> Triple("Failed", Icons.Default.Close, Color.Red)
            }

            Icon(
                imageVector = statusIcon,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = statusText,
                color = statusColor,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        LabelText(text = "DATE")
        Text(
            text = item.latestUpdateTimestamp.toActivityItemDate(),
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Spacer(modifier = Modifier.weight(1f))
    }
}

@DarkModePreview
@Composable
fun PreviewActivityItemView() {
    AppThemeSurface {
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
