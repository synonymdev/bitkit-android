package to.bitkit.ui.settings.lightning

import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ext.ellipsisMiddle
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.Toast
import to.bitkit.models.formatToModernDisplay
import to.bitkit.repositories.SparkExitMath
import to.bitkit.repositories.SparkExitPartialFunding
import to.bitkit.repositories.SparkExitQuote
import to.bitkit.repositories.SparkExitRecord
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.MoneyCaptionB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.rememberMoneyText
import to.bitkit.ui.screens.wallets.activity.Section
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard
import to.bitkit.ui.utils.getBlockExplorerUrl
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.utils.withAccentBoldBright
import to.bitkit.viewmodels.AppViewModel

/**
 * Opens the progress of an exit that started, from whichever exit screen shows: the start runs on when the user goes
 * back from Confirm meanwhile.
 */
@Composable
internal fun SparkExitStartedEffect(viewModel: SparkExitViewModel, onStarted: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isStarted = uiState.isStarted
    LaunchedEffect(isStarted) {
        if (!isStarted) return@LaunchedEffect
        viewModel.onStartShown()
        onStarted()
    }
}

/** An amount as plain text for copy that names it, in the user's primary unit. */
@Composable
internal fun sparkExitAmountText(sats: ULong): String =
    rememberMoneyText(sats.toLong(), showSymbol = true)?.replace(Regex("</?accent>"), "") ?: sats.toString()

/**
 * An amount the user sends from another wallet, always in bitcoin: the exit needs it exact, which a fiat figure
 * converted at another wallet's rate is not.
 */
@Composable
internal fun sparkExitSendAmountText(sats: ULong): String =
    rememberMoneyText(sats.toLong(), unit = PrimaryDisplay.BITCOIN, showSymbol = true)
        ?.replace(Regex("</?accent>"), "")
        ?: "$BITCOIN_SYMBOL ${sats.formatToModernDisplay()}"

/** [sparkExitSendAmountText] marked as an inline accent. */
@Composable
internal fun sparkExitSendAmountAccent(sats: ULong): String = "<accent>${sparkExitSendAmountText(sats)}</accent>"

/** The amount marked as an inline accent, for a line drawn with [withAccentBoldBright] or [withAccentBoldRed]. */
@Composable
internal fun sparkExitAmountAccent(sats: ULong): String = "<accent>${sparkExitAmountText(sats)}</accent>"

/** Bold red accents for a red line: its amounts stand out in the same red, the only red of the theme. */
internal fun String.withAccentBoldRed() =
    withAccent(accentStyle = SpanStyle(color = Colors.Red, fontWeight = FontWeight.Bold))

/** A force transfer headline: its accent is yellow, the force transfer's colour on every one of its screens. */
internal fun String.withExitAccent() = withAccent(accentColor = Colors.Yellow)

/** The caption heading a table of a force transfer screen. */
@Composable
internal fun SparkExitHeader(text: String, modifier: Modifier = Modifier) {
    Caption13Up(
        text = text,
        color = Colors.White64,
        modifier = modifier.padding(vertical = 16.dp)
    )
}

/** What the transfer moves, what the network takes and what reaches savings. */
@Composable
internal fun SparkExitQuoteRows(quote: SparkExitQuote) {
    Column {
        SparkExitHeader(stringResource(R.string.lightning__spark_exit__overview))
        SparkExitAmountRow(stringResource(R.string.lightning__spark_exit__amount), quote.exitSats)
        SparkExitAmountRow(stringResource(R.string.lightning__spark_exit__fees), quote.networkFeeSats)
        SparkExitAmountRow(stringResource(R.string.lightning__spark_exit__receive), quote.receiveSats)
    }
}

/** The emergency fees: one total, from savings or from another wallet. */
@Composable
internal fun SparkExitFeesRow(label: String, sats: ULong, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        SparkExitHeader(stringResource(R.string.lightning__spark_exit__fees_header))
        SparkExitAmountRow(label, sats)
    }
}

@Composable
private fun SparkExitAmountRow(label: String, sats: ULong) {
    SectionRow(name = label, valueContent = { MoneyCaptionB(sats = sats.toLong(), symbol = true) })
}

/** The funding address to pay from another wallet, with its copy button and what arrived so far. */
@Composable
internal fun SparkExitFundingAddress(
    address: String,
    requiredSats: ULong,
    seenSats: ULong,
    modifier: Modifier = Modifier,
    partial: SparkExitPartialFunding? = SparkExitMath.partialFunding(seenSats, requiredSats),
) {
    val app = appViewModel
    val copied = stringResource(R.string.common__copied)
    Column(modifier = modifier.fillMaxWidth()) {
        SparkExitHeader(stringResource(R.string.lightning__spark_exit__send_to_address))
        CaptionB(
            text = address,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("SparkExitFundingAddress")
        )
        VerticalSpacer(16.dp)
        PrimaryButton(
            text = stringResource(R.string.lightning__spark_exit__copy_address),
            size = ButtonSize.Small,
            fullWidth = false,
            onClick = copyToClipboard(address) { app.toastCopied(copied, it) },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = null,
                    tint = Colors.Yellow,
                    modifier = Modifier.size(16.dp)
                )
            },
            modifier = Modifier.testTag("SparkExitCopyAddress")
        )
        partial?.let {
            VerticalSpacer(16.dp)
            BodyS(
                text = stringResource(R.string.lightning__spark_exit__fund_partial)
                    .replace("{received}", sparkExitSendAmountAccent(it.receivedSats))
                    .replace("{required}", sparkExitSendAmountAccent(it.requiredSats))
                    .replace("{missing}", sparkExitSendAmountAccent(it.missingSats)).withAccentBoldBright(),
                color = Colors.White64,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("SparkExitFundingPartial")
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

/** The force transfer's steps: the emergency fees, publishing, the timelock and the sweep to savings. */
@Composable
internal fun SparkExitSteps(record: SparkExitRecord, modifier: Modifier = Modifier) {
    val transactions = record.recovery?.transactions.orEmpty()
    val isFunded = record.phase !is SparkExitRecord.Phase.AwaitingFunding
    // Retrying leaves the operators moved on-chain, every step of the exit itself is done.
    val isDone = record.phase == SparkExitRecord.Phase.Done || record.isRetryingFailedLeaves
    val fundingTxid = record.funding.firstOrNull()?.txid ?: record.fundingPaymentTxid
    val context = LocalContext.current
    Column(modifier = modifier) {
        SparkExitHeader(stringResource(R.string.lightning__spark_exit__progress_overview))
        SparkExitStep(
            title = stringResource(R.string.lightning__spark_exit__step_funding),
            isDone = isFunded,
            onExplore = fundingTxid?.let {
                {
                    context.startActivity(Intent(Intent.ACTION_VIEW, getBlockExplorerUrl(it).toUri()))
                }
            },
        )
        SparkExitStep(stringResource(R.string.lightning__spark_exit__step_publish), isDone = isDone)
        SparkExitStep(
            title = stringResource(R.string.lightning__spark_exit__step_timelock),
            isDone = isDone || SparkExitMath.areTimelocksMatured(transactions),
        )
        SparkExitStep(stringResource(R.string.lightning__spark_exit__step_sweep), isDone = isDone)
    }
}

@Composable
private fun SparkExitStep(title: String, isDone: Boolean, onExplore: (() -> Unit)? = null) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
    ) {
        Icon(
            painter = painterResource(if (isDone) R.drawable.ic_check else R.drawable.ic_clock),
            contentDescription = null,
            tint = if (isDone) Colors.Yellow else Colors.White64,
            modifier = Modifier.size(16.dp)
        )
        CaptionB(text = title, modifier = Modifier.weight(1f))
        onExplore?.let {
            PrimaryButton(
                text = null,
                size = ButtonSize.Small,
                fullWidth = false,
                onClick = it,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_git_branch),
                        contentDescription = stringResource(R.string.wallet__activity_explore),
                        tint = Colors.Yellow,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier.testTag("SparkExitFundingExplore")
            )
        }
    }
    HorizontalDivider()
}

private fun AppViewModel?.toastCopied(title: String, text: String) =
    this?.toast(type = Toast.ToastType.SUCCESS, title = title, description = text.ellipsisMiddle(40))
