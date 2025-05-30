package to.bitkit.ui.screens.wallets.activity.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ext.DatePattern
import to.bitkit.ext.formatted
import to.bitkit.ext.rawId
import to.bitkit.ext.totalValue
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.formatToModernDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.screens.wallets.activity.utils.previewActivityItems
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.animations.BalanceAnimations
import to.bitkit.ui.shared.UiConstants
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
            .clickableAlpha { onClick(item.rawId()) }
            .padding(vertical = 16.dp)
    ) {
        ActivityIcon(activity = item, size = 32.dp)
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f)
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
                        stringResource(R.string.wallet__activity_confirms_in).replace("{feeRateDescription}", "± 1h")
                    }
                }
            }
            CaptionB(
                text = subtitleText,
                color = Colors.White64,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
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
                    else -> Unit
                }

                else -> when (status) {
                    PaymentState.FAILED -> BodyMSB(text = stringResource(R.string.wallet__activity_failed))
                    PaymentState.PENDING -> BodyMSB(text = stringResource(R.string.wallet__activity_pending))
                    PaymentState.SUCCEEDED -> BodyMSB(text = stringResource(R.string.wallet__activity_received))
                    else -> Unit
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
    val amount = item.totalValue()

    val isPreview = LocalInspectionMode.current
    if (isPreview) {
        AmountViewContent(
            title = amount.toLong().formatToModernDisplay(),
            titlePrefix = prefix,
            subtitle = "123.45",
            subtitleSymbol = "$",
            hideBalance = false,
        )
        return
    }

    val settings = settingsViewModel ?: return
    val currency = currencyViewModel ?: return
    val (_, _, _, _, displayUnit, primaryDisplay) = LocalCurrencies.current

    val hideBalance by settings.hideBalance.collectAsStateWithLifecycle()

    currency.convert(sats = amount.toLong())?.let { converted ->
        val btcValue = converted.bitcoinDisplay(displayUnit).value
        if (primaryDisplay == PrimaryDisplay.BITCOIN) {
            AmountViewContent(
                title = btcValue,
                titlePrefix = prefix,
                subtitle = converted.formatted,
                subtitleSymbol = converted.symbol,
                hideBalance = hideBalance,
            )
        } else {
            AmountViewContent(
                title = converted.formatted,
                titleSymbol = converted.symbol,
                titlePrefix = prefix,
                subtitle = btcValue,
                hideBalance = hideBalance,
            )
        }
    }
}

@Composable
private fun AmountViewContent(
    title: String,
    titlePrefix: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    titleSymbol: String? = null,
    subtitleSymbol: String? = null,
    hideBalance: Boolean = false,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Title row with static prefix and symbol
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            BodyMSB(text = titlePrefix, color = Colors.White64)
            if (titleSymbol != null) {
                BodyMSB(text = titleSymbol, color = Colors.White64)
            }
            Spacer(modifier = Modifier.width(2.dp))
            AnimatedContent(
                targetState = hideBalance,
                transitionSpec = { BalanceAnimations.activityAmountTransition },
                label = "titleAnimation"
            ) { isHidden ->
                BodyMSB(text = if (isHidden) UiConstants.HIDE_BALANCE_SHORT else title)
            }
        }

        // Subtitle row with static symbol
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (subtitleSymbol != null) {
                CaptionB(text = subtitleSymbol, color = Colors.White64)
            }
            AnimatedContent(
                targetState = hideBalance,
                transitionSpec = { BalanceAnimations.activitySubtitleTransition },
                label = "subtitleAnimation"
            ) { isHidden ->
                CaptionB(
                    text = if (isHidden) UiConstants.HIDE_BALANCE_SHORT else subtitle,
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
    override val values: Sequence<Activity> get() = previewActivityItems.asSequence()
}

@Preview(showBackground = true)
@Composable
private fun Preview(@PreviewParameter(ActivityItemsPreviewProvider::class) item: Activity) {
    AppThemeSurface {
        ActivityRow(
            item = item,
            onClick = {},
        )
    }
}
