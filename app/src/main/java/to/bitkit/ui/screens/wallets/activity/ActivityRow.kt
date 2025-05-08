package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ext.toActivityItemDate
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.ActivityIcon
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.currencyViewModel
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
    val amountPrefix = if (txType == PaymentType.SENT) "-" else "+"
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
        ActivityIcon(item)
        Spacer(modifier = Modifier.width(12.dp))

        // TODO: use localized status texts
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
            val (_, _, _, _, displayUnit, primaryDisplay) = LocalCurrencies.current

            currency.convert(sats = sats.toLong())?.let { converted ->
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val btcComponents = converted.bitcoinDisplay(displayUnit)
                    if (primaryDisplay == PrimaryDisplay.BITCOIN) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            BodyMSB(text = amountPrefix, color = Colors.White64)
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
                            BodyMSB(text = amountPrefix, color = Colors.White64)
                            BodyMSB(text = "${converted.symbol} ${converted.formatted}")
                        }
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

private class ActivityItemsPreviewProvider : PreviewParameterProvider<Activity> {
    override val values: Sequence<Activity> get() = testActivityItems.asSequence()
}

@Preview(showBackground = true)
@Composable
private fun ActivityRowPreview(@PreviewParameter(ActivityItemsPreviewProvider::class) item: Activity) {
    AppThemeSurface {
        ActivityRow(
            item = item,
            onClick = {},
        )
    }
}
