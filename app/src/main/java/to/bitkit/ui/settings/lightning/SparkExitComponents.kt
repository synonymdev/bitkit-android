package to.bitkit.ui.settings.lightning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import breez_sdk_spark.RecoverFundsResponse
import to.bitkit.R
import to.bitkit.ext.setClipboardText
import to.bitkit.repositories.SparkExitMath
import to.bitkit.repositories.SparkExitQuote
import to.bitkit.repositories.SparkExitRecord
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.CaptionM
import to.bitkit.ui.components.MoneyCaptionB
import to.bitkit.ui.components.QrCodeImage
import to.bitkit.ui.components.rememberMoneyText
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.utils.BlockTimeHelpers

/** An amount as plain text for copy that names it, in the user's primary unit. */
@Composable
internal fun sparkExitAmountText(sats: ULong): String =
    rememberMoneyText(sats.toLong(), showSymbol = true)?.replace(Regex("</?accent>"), "") ?: sats.toString()

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

@Composable
private fun SparkExitAmountRow(label: String, sats: ULong) {
    SectionRow(name = label, valueContent = { MoneyCaptionB(sats = sats.toLong(), symbol = true) })
}

/** The funding address as QR and text, which copies it on tap, with what it must receive. */
@Composable
internal fun SparkExitFundingAddress(
    address: String,
    requiredSats: ULong,
    seenSats: ULong,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
        QrCodeImage(content = address, size = 200.dp)
        BodyS(
            text = stringResource(R.string.lightning__spark_exit__fund_external_text)
                .replace("{amount}", sparkExitAmountText(requiredSats)),
            color = Colors.White64,
            modifier = Modifier.fillMaxWidth()
        )
        CaptionB(
            text = address,
            maxLines = 2,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clickableAlpha { context.setClipboardText(address) }
                .testTag("SparkExitFundingAddress")
        )
        if (requiredSats > 0uL && seenSats >= requiredSats) {
            CaptionM(
                text = stringResource(R.string.lightning__spark_exit__fund_seen).withAccent(),
                color = Colors.Green,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** The exit's steps: funding, publishing, the timelock and the sweep. */
@Composable
internal fun SparkExitSteps(record: SparkExitRecord, tipHeight: UInt?) {
    val recovery: RecoverFundsResponse? = record.recovery
    val transactions = recovery?.transactions.orEmpty()
    val isFunded = record.phase !is SparkExitRecord.Phase.AwaitingFunding
    val isDone = record.phase == SparkExitRecord.Phase.Done
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
