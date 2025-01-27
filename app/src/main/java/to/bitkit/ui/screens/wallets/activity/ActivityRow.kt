package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import to.bitkit.ext.toActivityItemDate
import to.bitkit.models.ConvertedAmount
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.shared.util.DarkModePreview
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.secondaryColor
import to.bitkit.viewmodels.PrimaryDisplay
import uniffi.bitkitcore.Activity
import uniffi.bitkitcore.PaymentState
import uniffi.bitkitcore.PaymentType

@Composable
fun ActivityRow(
    item: Activity,
    onClick: (String) -> Unit,
) {
    val id = when (item) {
        is Activity.Onchain -> item.v1.id
        is Activity.Lightning -> item.v1.id
    }
    val status: PaymentState? = when (item) {
        is Activity.Lightning -> item.v1.status
        is Activity.Onchain -> null
    }
    val isLightning = item is Activity.Lightning
    val timestamp = when (item) {
        is Activity.Lightning -> item.v1.timestamp
        is Activity.Onchain -> item.v1.timestamp
    }
    val txType: PaymentType = when (item) {
        is Activity.Lightning -> item.v1.txType
        is Activity.Onchain -> item.v1.txType
    }
    val amountPrefix = when (item) {
        is Activity.Lightning -> if (item.v1.txType == PaymentType.SENT) "-" else "+"
        is Activity.Onchain -> if (item.v1.txType == PaymentType.SENT) "-" else "+"
    }
    val confirmed: Boolean? = when (item) {
        is Activity.Lightning -> null
        is Activity.Onchain -> item.v1.confirmed
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { onClick(id) })
            .padding(horizontal = 0.dp, vertical = 16.dp)
    ) {
        TransactionIcon(item)
        Spacer(modifier = Modifier.width(12.dp))

        val lightningStatus = when {
            txType == PaymentType.SENT -> when (status) {
                PaymentState.FAILED -> "Sending Failed"
                PaymentState.PENDING -> "Sending..."
                PaymentState.SUCCEEDED -> "Sent"
                else -> ""
            }

            else -> when (status) {
                PaymentState.FAILED -> "Receive Failed"
                PaymentState.PENDING -> "Receiving..."
                PaymentState.SUCCEEDED -> "Received"
                else -> ""
            }
        }
        val onchainStatus = when {
            txType == PaymentType.SENT -> if (confirmed == true) "Sent" else "Sending..."
            else -> if (confirmed == true) "Received" else "Receiving..."
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = if (isLightning) lightningStatus else onchainStatus,
                fontWeight = FontWeight.Bold,
            )
            // TODO timestamp: if today - only hour
            val subtitleText = when (item) {
                is Activity.Lightning -> {
                    item.v1.message.ifEmpty { timestamp.toActivityItemDate() }
                }

                else -> timestamp.toActivityItemDate()
            }
            Text(
                text = subtitleText,
                color = Colors.White64,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        val amount: ULong = when (item) {
            is Activity.Lightning -> item.v1.value
            is Activity.Onchain -> item.v1.value
        }
        amount.let { sats ->
            val currency = currencyViewModel ?: return
            val (rates, _, _, _, displayUnit, primaryDisplay) = LocalCurrencies.current
            val converted: ConvertedAmount? =
                if (rates.isNotEmpty()) currency.convert(sats = sats.toLong()) else null

            converted?.let { converted ->
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (primaryDisplay == PrimaryDisplay.BITCOIN) {
                        val btcComponents = converted.bitcoinDisplay(displayUnit)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text(
                                text = amountPrefix,
                                color = secondaryColor,
                            )
                            Text(text = btcComponents.value)
                        }
                        Text(
                            text = "${converted.symbol} ${converted.formatted}",
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryColor,
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text(
                                text = amountPrefix,
                                color = secondaryColor,
                            )
                            Text(text = "${converted.symbol} ${converted.formatted}")
                        }

                        val btcComponents = converted.bitcoinDisplay(displayUnit)
                        Text(
                            text = btcComponents.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TransactionIcon(item: Activity) {
    val isLightning = item is Activity.Lightning
    val status: PaymentState? = when (item) {
        is Activity.Lightning -> item.v1.status
        is Activity.Onchain -> null
    }
    val confirmed: Boolean? = when (item) {
        is Activity.Lightning -> null
        is Activity.Onchain -> item.v1.confirmed
    }
    val txType: PaymentType = when (item) {
        is Activity.Lightning -> item.v1.txType
        is Activity.Onchain -> item.v1.txType
    }

    if (isLightning) {
        if (status == PaymentState.FAILED) {
            IconInCircle(
                icon = Icons.Default.Close,
                tint = Colors.Red,
            )
        } else {
            val icon = if (txType == PaymentType.SENT) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward
            IconInCircle(
                icon = icon,
                tint = Colors.Purple,
            )
        }
    } else {
        val icon = if (txType == PaymentType.SENT) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward
        IconInCircle(
            icon = icon,
            tint = if (confirmed == true) Colors.Brand else Colors.Brand50,
        )
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

private class ActivityItemsPreviewProvider : PreviewParameterProvider<Activity> {
    override val values: Sequence<Activity> get() = testActivityItems.asSequence()
}

@DarkModePreview
@Composable
private fun ActivityRowPreview(@PreviewParameter(ActivityItemsPreviewProvider::class) item: Activity) {
    AppThemeSurface {
        ActivityRow(item, onClick = { })
    }
}
