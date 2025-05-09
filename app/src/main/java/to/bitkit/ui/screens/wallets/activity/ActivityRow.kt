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
import to.bitkit.ext.DatePattern
import to.bitkit.ext.formatted
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun ActivityRow(
    item: Activity,
    onClick: (String) -> Unit,
) {
    val id = when (item) {
        is Activity.Onchain -> item.v1.id
        is Activity.Lightning -> item.v1.id
    }
    ActivityRowContent(
        item = item,
        onClick = { onClick(id) },
    )
}

@Composable
private fun ActivityRowContent(
    item: Activity,
    onClick: () -> Unit,
) {
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
    val isSent = txType == PaymentType.SENT
    val amountPrefix = if (isSent) "-" else "+"
    val confirmed: Boolean? = when (item) {
        is Activity.Lightning -> null
        is Activity.Onchain -> item.v1.confirmed
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickableAlpha { onClick() }
            .padding(vertical = 16.dp)
    ) {
        ActivityIcon(activity = item, size = 32.dp)
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TransactionStatusText(
                txType = txType,
                isLightning = isLightning,
                status = status,
                confirmed = confirmed,
            )
            val subtitleText = when (item) {
                is Activity.Lightning -> item.v1.message.ifEmpty { formattedTime(timestamp) }
                is Activity.Onchain -> {
                    if (confirmed == true) {
                        formattedTime(timestamp)
                    } else {
                        // TODO: calculate confirmsIn text
                        stringResource(R.string.wallet__activity_confirms_in).replace("{feeRateDescription}", "???")
                    }
                }
            }
            CaptionB(
                text = subtitleText,
                color = Colors.White64,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        AmountView(
            item = item,
            prefix = amountPrefix,
        )
    }
}

@Composable
private fun TransactionStatusText(
    txType: PaymentType,
    isLightning: Boolean,
    status: PaymentState?,
    confirmed: Boolean?,
) {
    when {
        isLightning -> {
            when (txType) {
                PaymentType.SENT -> when (status) {
                    PaymentState.FAILED -> BodyMSB(text = stringResource(R.string.wallet__activity_failed))
                    PaymentState.PENDING -> BodyMSB(text = stringResource(R.string.wallet__activity_pending))
                    PaymentState.SUCCEEDED -> BodyMSB(text = stringResource(R.string.wallet__activity_sent))
                    else -> {}
                }

                else -> when (status) {
                    PaymentState.FAILED -> BodyMSB(text = stringResource(R.string.wallet__activity_failed))
                    PaymentState.PENDING -> BodyMSB(text = stringResource(R.string.wallet__activity_pending))
                    PaymentState.SUCCEEDED -> BodyMSB(text = stringResource(R.string.wallet__activity_received))
                    else -> {}
                }
            }
        }

        else -> {
            when (txType) {
                PaymentType.SENT -> BodyMSB(text = stringResource(R.string.wallet__activity_sent))
                else -> BodyMSB(text = stringResource(R.string.wallet__activity_received))
            }
        }
    }
}

@Composable
private fun AmountView(
    item: Activity,
    prefix: String,
) {
    val currency = currencyViewModel ?: return
    val (_, _, _, _, displayUnit, primaryDisplay) = LocalCurrencies.current
    val amount = when (item) {
        is Activity.Lightning -> item.v1.value
        is Activity.Onchain -> when (item.v1.txType) {
            PaymentType.SENT -> item.v1.value + item.v1.fee
            else -> item.v1.value
        }
    }
    currency.convert(sats = amount.toLong())?.let { converted ->
        val btcComponents = converted.bitcoinDisplay(displayUnit)
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (primaryDisplay == PrimaryDisplay.BITCOIN) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    BodyMSB(text = prefix, color = Colors.White64)
                    BodyMSB(text = btcComponents.value)
                }
                CaptionB(
                    text = "${converted.symbol} ${converted.formatted}",
                    color = Colors.White64,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    BodyMSB(text = prefix, color = Colors.White64)
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

private fun formattedTime(timestamp: ULong): String {
    val instant = Instant.ofEpochSecond(timestamp.toLong())
    val dateTime = instant.atZone(ZoneId.systemDefault())
    val now = LocalDate.now()

    val isToday = dateTime.toLocalDate() == now
    val isThisYear = dateTime.year == now.year
    return when {
        isToday -> instant.formatted(DatePattern.ACTIVITY_TIME)
        isThisYear -> instant.formatted(DatePattern.ACTIVITY_ROW_DATE)
        else -> instant.formatted(DatePattern.ACTIVITY_ROW_DATE_YEAR)
    }
}

private class ActivityItemsPreviewProvider : PreviewParameterProvider<Activity> {
    override val values: Sequence<Activity> get() = testActivityItems.asSequence()
}

@Preview(showBackground = true)
@Composable
private fun Preview(@PreviewParameter(ActivityItemsPreviewProvider::class) item: Activity) {
    AppThemeSurface {
        ActivityRowContent(
            item = item,
            onClick = {},
        )
    }
}
