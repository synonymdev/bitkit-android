package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ext.toActivityItemDate
import to.bitkit.ui.Routes
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.LabelText
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.DarkModePreview
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.ActivityListViewModel
import uniffi.bitkitcore.Activity
import uniffi.bitkitcore.PaymentState
import uniffi.bitkitcore.PaymentType

@Composable
fun ActivityItemScreen(
    viewModel: ActivityListViewModel,
    activityItem: Routes.ActivityItem,
    onBackClick: () -> Unit,
) {
    val filteredActivities by viewModel.filteredActivities.collectAsState()
    val item = filteredActivities?.find {
        val id = when (it) {
            is Activity.Onchain -> it.v1.id
            is Activity.Lightning -> it.v1.id
        }
        id == activityItem.id
    } ?: return

    ScreenColumn {
        AppTopBar("Activity Details", onBackClick = onBackClick)
        ActivityItemView(item)
    }
}

@Composable
private fun ActivityItemView(
    item: Activity,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val amountSats: ULong = when (item) {
                is Activity.Lightning -> item.v1.value
                is Activity.Onchain -> item.v1.value
            }
            val amountPrefix = when (item) {
                is Activity.Lightning -> if (item.v1.txType == PaymentType.SENT) "-" else "+"
                is Activity.Onchain -> if (item.v1.txType == PaymentType.SENT) "-" else "+"
            }
            BalanceHeaderView(
                sats = amountSats.toLong(),
                prefix = amountPrefix,
                showBitcoinSymbol = false,
                modifier = Modifier.weight(1f),
            )
            TransactionIcon(item = item)
        }

        Spacer(modifier = Modifier.height(12.dp))
        StatusSection(item)
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        LabelText(text = "DATE")
        Spacer(modifier = Modifier.height(8.dp))

        val timestamp = when (item) {
            is Activity.Lightning -> item.v1.timestamp
            is Activity.Onchain -> item.v1.timestamp
        }
        Text(text = timestamp.toActivityItemDate())

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatusSection(item: Activity) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LabelText(text = stringResource(R.string.label_status))
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            when (item) {
                is Activity.Lightning -> {
                    LightningStatusView(status = item.v1.status)
                }

                is Activity.Onchain -> {
                    OnchainStatusView(confirmed = item.v1.confirmed)
                }
            }
        }
    }
}

@Composable
private fun LightningStatusView(status: PaymentState?) {
    when (status) {
        PaymentState.PENDING -> {
            StatusIcon(Icons.Default.HourglassEmpty, Colors.Purple)
            StatusText("Pending", Colors.Purple)
        }

        PaymentState.SUCCEEDED -> {
            StatusIcon(Icons.Default.Bolt, Colors.Purple)
            StatusText("Successful", Colors.Purple)
        }

        PaymentState.FAILED -> {
            StatusIcon(Icons.Default.Close, Colors.Red)
            StatusText("Failed", Colors.Red)
        }

        null -> Unit
    }
}

@Composable
private fun OnchainStatusView(confirmed: Boolean?) {
    when (confirmed) {
        true -> {
            StatusIcon(Icons.Outlined.CheckCircle, Colors.Green)
            StatusText("Confirmed", Colors.Green)
        }

        else -> {
            StatusIcon(Icons.Default.HourglassEmpty, Colors.Brand)
            StatusText("Confirming", Colors.Brand)
        }
    }
}

@Composable
private fun StatusIcon(
    icon: ImageVector,
    tint: Color,
    contentDescription: String? = null,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier.size(20.dp)
    )
}

@Composable
private fun StatusText(
    text: String,
    color: Color,
) {
    Text(
        text = text,
        color = color,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@DarkModePreview
@Composable
private fun PreviewActivityItemView() {
    AppThemeSurface {
        ActivityItemView(item = testActivityItems[1])
    }
}
