package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ext.toActivityItemDate
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.shared.util.DarkModePreview
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
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
            .clickableAlpha { onClick(id) }
            .padding(vertical = 16.dp)
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
            txType == PaymentType.SENT -> if (confirmed == true) stringResource(R.string.wallet__activity_sent) else "Sending..."
            else -> if (confirmed == true) stringResource(R.string.wallet__activity_received) else "Receiving..."
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            BodyMSB(text = if (isLightning) lightningStatus else onchainStatus)
            // TODO timestamp: if today - only hour
            val subtitleText = when (item) {
                is Activity.Lightning -> {
                    item.v1.message.ifEmpty { timestamp.toActivityItemDate() }
                }

                else -> timestamp.toActivityItemDate()
            }
            CaptionB(
                text = subtitleText,
                color = Colors.White64,
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
                            BodyMSB(
                                text = amountPrefix,
                                color = Colors.White64,
                            )
                            BodyMSB(text = btcComponents.value)
                        }
                        CaptionB(
                            text = "${converted.symbol} ${converted.formatted}",
                            color = Colors.White64,
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            BodyMSB(
                                text = amountPrefix,
                                color = Colors.White64,
                            )
                            BodyMSB(text = "${converted.symbol} ${converted.formatted}")
                        }

                        val btcComponents = converted.bitcoinDisplay(displayUnit)
                        CaptionB(
                            text = btcComponents.value,
                            color = Colors.White64,
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
