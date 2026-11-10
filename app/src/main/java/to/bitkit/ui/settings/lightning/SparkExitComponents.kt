package to.bitkit.ui.settings.lightning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import breez_sdk_spark.RecoverFundsResponse
import to.bitkit.R
import to.bitkit.ext.ellipsisMiddle
import to.bitkit.models.Toast
import to.bitkit.repositories.SparkExitMath
import to.bitkit.repositories.SparkExitPartialFunding
import to.bitkit.repositories.SparkExitQuote
import to.bitkit.repositories.SparkExitRecord
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.MoneyCaptionB
import to.bitkit.ui.components.QrCodeImage
import to.bitkit.ui.components.rememberMoneyText
import to.bitkit.ui.screens.wallets.activity.Section
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.utils.withAccentBoldBright
import to.bitkit.utils.BlockTimeHelpers
import to.bitkit.viewmodels.AppViewModel

/** An amount as plain text for copy that names it, in the user's primary unit. */
@Composable
internal fun sparkExitAmountText(sats: ULong): String =
    rememberMoneyText(sats.toLong(), showSymbol = true)?.replace(Regex("</?accent>"), "") ?: sats.toString()

/**
 * What Display's line height adds under a headline on Android against iOS: master's spacers under an exit headline
 * render 9 dp more here, so exit screens take it off and look the same on both apps. Measured on 2x media devices.
 */
internal val SparkExitDisplayExtra = 9.dp

/** The amount marked as an inline accent, for a line drawn with [withAccentBoldBright] or [withAccentBoldRed]. */
@Composable
internal fun sparkExitAmountAccent(sats: ULong): String = "<accent>${sparkExitAmountText(sats)}</accent>"

/** Bold red accents for a red line: its amounts stand out in the same red, the only red of the theme. */
internal fun String.withAccentBoldRed() =
    withAccent(accentStyle = SpanStyle(color = Colors.Red, fontWeight = FontWeight.Bold))

@Composable
internal fun SparkExitQuoteRows(quote: SparkExitQuote) {
    Column {
        SparkExitAmountRow(stringResource(R.string.lightning__spark_exit__amount), quote.exitSats)
        SparkExitAmountRow(stringResource(R.string.lightning__spark_exit__fees), quote.networkFeeSats)
        SparkExitAmountRow(stringResource(R.string.lightning__spark_exit__receive), quote.receiveSats)
        if (quote.fundingSats > 0uL) {
            SparkExitAmountRow(stringResource(R.string.lightning__spark_exit__funding), quote.fundingSats)
        }
        if (quote.leftBehindSats > 0uL) {
            SparkExitAmountRow(stringResource(R.string.lightning__spark_exit__left_behind), quote.leftBehindSats)
        }
    }
}

/** What paying the fee funding from savings costs: the fee of that payment and all that leaves savings. */
@Composable
internal fun SparkExitSavingsRows(feeSats: ULong, totalSats: ULong) {
    Column {
        SparkExitAmountRow(stringResource(R.string.lightning__spark_exit__savings_fee), feeSats)
        SparkExitAmountRow(stringResource(R.string.lightning__spark_exit__savings_total), totalSats)
    }
}

@Composable
private fun SparkExitAmountRow(label: String, sats: ULong) {
    SectionRow(name = label, valueContent = { MoneyCaptionB(sats = sats.toLong(), symbol = true) })
}

/** The funding address as QR and text, which copies it on tap, with what it must receive and what arrived so far. */
@Composable
internal fun SparkExitFundingAddress(
    address: String,
    requiredSats: ULong,
    seenSats: ULong,
    modifier: Modifier = Modifier,
    partial: SparkExitPartialFunding? = SparkExitMath.partialFunding(seenSats, requiredSats),
    isCovered: Boolean = requiredSats > 0uL && seenSats >= requiredSats,
) {
    val app = appViewModel
    val copied = stringResource(R.string.common__copied)
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
        // The receive screen's tile, at a fixed size with black space around it.
        QrCodeImage(
            content = address,
            size = 200.dp,
            modifier = Modifier
                .padding(vertical = 16.dp)
                .size(200.dp)
        )
        BodyS(
            text = stringResource(R.string.lightning__spark_exit__fund_external_text)
                .replace("{amount}", sparkExitAmountAccent(requiredSats)).withAccentBoldBright(),
            color = Colors.White64,
            modifier = Modifier.fillMaxWidth()
        )
        CaptionB(
            text = address,
            maxLines = 2,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clickableAlpha(onClick = copyToClipboard(address) { app.toastCopied(copied, it) })
                .testTag("SparkExitFundingAddress")
        )
        partial?.let {
            BodyS(
                text = stringResource(R.string.lightning__spark_exit__fund_partial)
                    .replace("{received}", sparkExitAmountAccent(it.receivedSats))
                    .replace("{required}", sparkExitAmountAccent(it.requiredSats))
                    .replace("{missing}", sparkExitAmountAccent(it.missingSats)).withAccentBoldBright(),
                color = Colors.White64,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("SparkExitFundingPartial")
            )
        }
        if (isCovered) {
            Caption(
                text = stringResource(R.string.lightning__spark_exit__fund_seen),
                color = Colors.Green,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** A line of progress with the transaction it is about, in the activity explore cell whose id copies on tap. */
@Composable
internal fun SparkExitTxNote(text: String?, txid: String, testTag: String) {
    val app = appViewModel
    val copied = stringResource(R.string.common__copied)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        text?.let { BodyS(text = it.withAccentBoldBright(), color = Colors.White64) }
        Section(
            title = stringResource(R.string.wallet__activity_tx_id),
            value = txid,
            modifier = Modifier.clickableAlpha(
                onClick = copyToClipboard(txid) {
                    app.toastCopied(copied, it)
                }
            )
        )
    }
}

/** The exit's steps: funding, publishing, the timelock and the sweep. */
@Composable
internal fun SparkExitSteps(record: SparkExitRecord, tipHeight: UInt?) {
    val recovery: RecoverFundsResponse? = record.recovery
    val transactions = recovery?.transactions.orEmpty()
    val isFunded = record.phase !is SparkExitRecord.Phase.AwaitingFunding
    // Retrying leaves the operators moved on-chain, every step of the exit itself is done.
    val isDone = record.phase == SparkExitRecord.Phase.Done || record.isRetryingFailedLeaves
    val remaining = record.claimableAtHeight?.let { target ->
        tipHeight?.let { BlockTimeHelpers.blocksRemaining(target, it) }
    }?.takeIf { it > 0 }?.let(BlockTimeHelpers::getDurationForBlocks)
    Column {
        SparkExitStep(stringResource(R.string.lightning__spark_exit__step_funding), isDone = isFunded)
        SparkExitStep(
            title = stringResource(R.string.lightning__spark_exit__step_publish),
            detail = recovery?.let { "${SparkExitMath.confirmedCount(it)}/${transactions.size}" },
            isDone = isDone,
        )
        SparkExitStep(
            title = stringResource(R.string.lightning__spark_exit__step_timelock),
            detail = remaining?.let {
                stringResource(R.string.lightning__spark_exit__ready_in).replace("{duration}", it)
            },
            isDone = isDone || SparkExitMath.areTimelocksMatured(transactions),
        )
        SparkExitStep(stringResource(R.string.lightning__spark_exit__step_sweep), isDone = isDone)
    }
}

@Composable
private fun SparkExitStep(title: String, isDone: Boolean, detail: String? = null) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
    ) {
        Icon(
            painter = painterResource(if (isDone) R.drawable.ic_check else R.drawable.ic_hourglass_simple),
            contentDescription = null,
            tint = if (isDone) Colors.Green else Colors.White64,
            modifier = Modifier.size(20.dp)
        )
        CaptionB(text = title, modifier = Modifier.weight(1f))
        detail?.let { CaptionB(text = it, color = Colors.White64) }
    }
    HorizontalDivider()
}

private fun AppViewModel?.toastCopied(title: String, text: String) =
    this?.toast(type = Toast.ToastType.SUCCESS, title = title, description = text.ellipsisMiddle(40))
