package to.bitkit.ui.settings.lightning

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.repositories.CANCELLED_REASON
import to.bitkit.repositories.SparkExitBackgroundLimits
import to.bitkit.repositories.SparkExitFundingState
import to.bitkit.repositories.SparkExitRecord
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.openNotificationSettings
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccentBoldBright

/** The running exit's steps and countdown, and its result once it ends. */
@Composable
fun SparkExitProgressScreen(
    viewModel: SparkExitViewModel,
    onDone: () -> Unit,
    onWalletOverview: () -> Unit,
    onPayHigherFee: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.refresh(showIndicator = false)
        viewModel.effects.collect { if (it == SparkExitEffect.Cancelled) onDone() }
    }
    val isEnded = uiState.record?.isActive == false
    DisposableEffect(Unit) {
        viewModel.startSavingsRetryPricing()
        onDispose { viewModel.stopSavingsRetryPricing() }
    }
    LaunchedEffect(uiState.record?.savingsRefusal) { viewModel.loadSavingsRetry() }
    LaunchedEffect(isEnded) { if (isEnded) viewModel.onResultShown() }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshBackgroundLimits()
        onPauseOrDispose {}
    }
    Content(
        uiState = uiState,
        onRefresh = viewModel::refresh,
        onCancel = viewModel::cancel,
        onDone = onDone,
        onWalletOverview = onWalletOverview,
        onNotificationSettings = context::openNotificationSettings,
        onPayHigherFee = onPayHigherFee,
        onRetrySavingsPayment = viewModel::retrySavingsPayment,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Content(
    uiState: SparkExitUiState,
    onRefresh: () -> Unit = {},
    onCancel: () -> Unit = {},
    onDone: () -> Unit = {},
    onWalletOverview: () -> Unit = {},
    onNotificationSettings: () -> Unit = {},
    onPayHigherFee: () -> Unit = {},
    onRetrySavingsPayment: () -> Unit = {},
) {
    val record = uiState.record
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__spark_exit__progress_nav_title),
            onBackClick = null,
            actions = { DrawerNavIcon() },
        )
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("SparkExitProgress")
            ) {
                VerticalSpacer(32.dp)
                Display(text = progressTitle(record?.phase, uiState.progressFunding).withExitAccent())
                BodyM(
                    text = progressText(
                        record = record,
                        progressFunding = uiState.progressFunding,
                        staysOnSparkSats = uiState.staysOnSparkSats,
                    ).withAccentBoldBright(),
                    color = Colors.White64,
                )
                VerticalSpacer(16.dp)
                record?.let { ProgressDetails(it, uiState, onPayHigherFee) }
                if (record?.isActive == true) BackgroundNotes(uiState.backgroundLimits)
                VerticalSpacer(32.dp)
            }
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            if (record?.isActive == true && !uiState.backgroundLimits.areNotificationsEnabled) {
                SecondaryButton(
                    text = stringResource(R.string.lightning__spark_exit__note_notifications_button),
                    onClick = onNotificationSettings,
                    testTag = "SparkExitNotificationSettings",
                )
                VerticalSpacer(16.dp)
            }
            if (uiState.offersSavingsRetry) {
                SecondaryButton(
                    text = stringResource(R.string.lightning__spark_exit__fund_savings),
                    onClick = onRetrySavingsPayment,
                    isLoading = uiState.isRetryingSavingsPayment,
                    testTag = "SparkExitRetrySavings",
                )
                VerticalSpacer(16.dp)
            }
            if (uiState.canPayHigherFee) {
                SecondaryButton(
                    text = stringResource(R.string.lightning__spark_exit__fee_bump_button),
                    onClick = onPayHigherFee,
                    testTag = "SparkExitPayHigherFee",
                )
                VerticalSpacer(16.dp)
            }
            if (uiState.canCancel) {
                var isConfirming by remember { mutableStateOf(false) }
                SecondaryButton(
                    text = stringResource(R.string.lightning__spark_exit__cancel),
                    onClick = { isConfirming = true },
                    testTag = "SparkExitCancel",
                )
                VerticalSpacer(16.dp)
                if (isConfirming) {
                    AppAlertDialog(
                        title = stringResource(R.string.lightning__spark_exit__cancel_title),
                        text = stringResource(R.string.lightning__spark_exit__cancel_text),
                        confirmText = stringResource(R.string.lightning__spark_exit__cancel),
                        dismissText = stringResource(R.string.common__cancel),
                        confirmTextColor = Colors.Red,
                        onConfirm = {
                            isConfirming = false
                            onCancel()
                        },
                        onDismiss = { isConfirming = false },
                        confirmTestTag = "SparkExitCancelConfirm",
                    )
                }
            }
            if (record?.phase == SparkExitRecord.Phase.Done) {
                PrimaryButton(
                    text = stringResource(R.string.lightning__spark_exit__done_button),
                    onClick = onWalletOverview,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("SparkExitWalletOverview")
                )
            } else {
                PrimaryButton(
                    text = stringResource(
                        when (record?.phase) {
                            is SparkExitRecord.Phase.Failed, null -> R.string.common__ok
                            else -> R.string.lightning__setting_up_button
                        }
                    ),
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("SparkExitProgressOk")
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun ProgressDetails(record: SparkExitRecord, uiState: SparkExitUiState, onPayHigherFee: () -> Unit) {
    SparkExitSteps(record = record)
    uiState.returned?.let { ReturnedNote(receivedSats = it.spendingSats, feesSats = it.feesSats) }
    val address = record.funding.firstOrNull()?.address
    val funding = uiState.progressFunding
    if (uiState.offersSavingsRetry) {
        VerticalSpacer(24.dp)
        BodyS(
            text = stringResource(R.string.lightning__spark_exit__savings_refused),
            color = Colors.Red,
            modifier = Modifier.testTag("SparkExitSavingsRefused")
        )
    }
    if (funding is SparkExitFundingState.Ask && address != null) {
        VerticalSpacer(24.dp)
        SparkExitFeesRow(
            label = stringResource(R.string.lightning__spark_exit__pay_amount),
            sats = funding.sats,
            modifier = Modifier.testTag("SparkExitProgressFundingAmount")
        )
        // The new fee to confirm: what leaves savings once Pay from savings is tapped.
        uiState.savingsRetryOffer?.let {
            SparkExitAmountRow(
                label = stringResource(R.string.lightning__spark_exit__fees_savings),
                sats = it.totalSats,
                modifier = Modifier.testTag("SparkExitProgressSavingsTotal")
            )
        }
        VerticalSpacer(32.dp)
        SparkExitFundingAddress(
            address = address,
            requiredSats = funding.sats,
            seenSats = 0uL,
            partial = funding.partial,
        )
    }
    if (record.isRebuiltFromChain && record.isActive) {
        VerticalSpacer(16.dp)
        BodyS(
            text = stringResource(R.string.lightning__spark_exit__rebuilt_from_chain),
            color = Colors.White64,
            modifier = Modifier.testTag("SparkExitRebuiltFromChain")
        )
    }
    if (record.isWaitingForOperators) {
        VerticalSpacer(16.dp)
        BodyS(
            text = stringResource(R.string.lightning__spark_exit__waiting_operators)
                .replace("{amount}", sparkExitAmountAccent(record.failedLeafSats)).withAccentBoldBright(),
            color = Colors.White64,
            modifier = Modifier.testTag("SparkExitWaitingOperators")
        )
    }
    if (record.isWaitingForLeaves && record.isActive) {
        VerticalSpacer(16.dp)
        BodyS(
            text = stringResource(R.string.lightning__spark_exit__waiting_leaves),
            color = Colors.White64,
            modifier = Modifier.testTag("SparkExitWaitingLeaves")
        )
    }
    uiState.rejection?.let {
        VerticalSpacer(16.dp)
        BodyS(
            text = stringResource(R.string.lightning__spark_exit__not_moving).replace("{error}", it),
            color = Colors.Red,
            modifier = Modifier.testTag("SparkExitNotMoving")
        )
    }
    uiState.lowFeeRate?.let {
        VerticalSpacer(16.dp)
        BodyS(
            text = stringResource(R.string.lightning__spark_exit__fee_low).replace("{rate}", "$it"),
            color = Colors.White64,
            modifier = Modifier.testTag("SparkExitFeeLow")
        )
    }
    if (uiState.showsFeeRose) {
        VerticalSpacer(16.dp)
        BodyS(
            text = stringResource(R.string.lightning__spark_exit__fee_rose),
            color = Colors.Yellow,
            modifier = Modifier
                .clickableAlpha(onClick = onPayHigherFee)
                .testTag("SparkExitFeeRose")
        )
    }
    if (record.lostSats > 0uL) {
        VerticalSpacer(16.dp)
        BodyS(
            text = stringResource(R.string.lightning__spark_exit__lost_leaves)
                .replace("{amount}", sparkExitAmountAccent(record.lostSats)).withAccentBoldRed(),
            color = Colors.Red,
            modifier = Modifier.testTag("SparkExitLostLeaves")
        )
    }
    if (record.leftBehindLeafSats > 0uL) {
        VerticalSpacer(16.dp)
        BodyS(
            text = stringResource(R.string.lightning__spark_exit__left_behind_leaves)
                .replace("{amount}", sparkExitAmountAccent(record.leftBehindLeafSats)).withAccentBoldBright(),
            color = Colors.White64,
            modifier = Modifier.testTag("SparkExitLeftBehindLeaves")
        )
    }
    if (!record.isActive && address != null) {
        val isSweptCounted = record.phase == SparkExitRecord.Phase.Done && (uiState.returned?.feesSats ?: 0uL) > 0uL
        LeftoverNote(record, address, uiState.fundingPath, isSweptCounted)
    }
}

/**
 * Where the leftover fee funding of an ended exit is: being sent, sent, kept as dust, or still at its address. When
 * the returned note already counts the confirmed sweeps as unused fees ([isSweptCounted]), they get no note here.
 */
@Composable
private fun LeftoverNote(record: SparkExitRecord, address: String, path: String?, isSweptCounted: Boolean) {
    // Sweeps that already confirmed come first; funding that arrived after them has a note of its own below.
    if (!isSweptCounted) {
        record.sweptLeftover.forEach {
            VerticalSpacer(16.dp)
            SparkExitTxNote(
                text = stringResource(R.string.lightning__spark_exit__leftover_swept)
                    .replace("{amount}", sparkExitAmountAccent(it.valueSats)),
                txid = it.txid,
                testTag = "SparkExitLeftoverSweptEarlier",
            )
        }
    }
    if (record.leftover.isEmpty()) return
    val total = record.leftover.fold(0uL) { sum, leftover -> sum + leftover.valueSats }
    val sweep = record.leftoverSweep
    if (isSweptCounted && sweep?.txid != null && sweep.isConfirmed) return
    VerticalSpacer(16.dp)
    when {
        record.isLeftoverWaitingForFees -> BodyS(
            text = stringResource(R.string.lightning__spark_exit__leftover_waiting)
                .replace("{amount}", sparkExitAmountAccent(total)).withAccentBoldBright(),
            color = Colors.White64,
            modifier = Modifier.testTag("SparkExitLeftoverWaiting")
        )
        sweep != null && sweep.txid == null -> BodyS(
            text = stringResource(R.string.lightning__spark_exit__leftover_dust)
                .replace("{amount}", sparkExitAmountAccent(total))
                .replace("{address}", address)
                .replace("{path}", path.orEmpty()).withAccentBoldBright(),
            color = Colors.White64,
        )
        // A sweep that confirmed reached savings: sent, no longer sending.
        sweep?.txid != null && sweep.isConfirmed -> SparkExitTxNote(
            text = stringResource(R.string.lightning__spark_exit__leftover_swept)
                .replace("{amount}", sparkExitAmountAccent(sweep.valueSats)),
            txid = sweep.txid,
            testTag = "SparkExitLeftoverSwept",
        )
        // Its txid shows once the network accepted the sweep, as it may never go out before that.
        sweep?.txid != null && sweep.isAccepted -> SparkExitTxNote(
            text = stringResource(R.string.lightning__spark_exit__leftover_sending)
                .replace("{amount}", sparkExitAmountAccent(sweep.valueSats)),
            txid = sweep.txid,
            testTag = "SparkExitLeftoverSending",
        )
        sweep?.txid != null && sweep.rejection == null -> BodyS(
            text = stringResource(R.string.lightning__spark_exit__leftover_sending)
                .replace("{amount}", sparkExitAmountAccent(sweep.valueSats)).withAccentBoldBright(),
            color = Colors.White64,
        )
        // Refused for good: where the leftover stays, with the path a wallet needs to find it.
        sweep?.rejection != null && path != null -> BodyS(
            text = stringResource(R.string.lightning__spark_exit__leftover_rejected)
                .replace("{amount}", sparkExitAmountAccent(total))
                .replace("{address}", address)
                .replace("{path}", path).withAccentBoldBright(),
            color = Colors.White64,
            modifier = Modifier.testTag("SparkExitLeftoverRejected")
        )
        else -> BodyS(
            text = stringResource(R.string.lightning__spark_exit__leftover)
                .replace("{amount}", sparkExitAmountAccent(total))
                .replace("{address}", address).withAccentBoldBright(),
            color = Colors.White64,
        )
    }
}

@Composable
private fun BackgroundNotes(limits: SparkExitBackgroundLimits) {
    val notes = listOfNotNull(
        R.string.lightning__spark_exit__note_restricted.takeIf { limits.isRestricted },
        R.string.lightning__spark_exit__note_battery_saver.takeIf { limits.isPowerSaveMode },
        R.string.lightning__spark_exit__note_notifications.takeIf { !limits.areNotificationsEnabled },
    )
    notes.forEach {
        VerticalSpacer(16.dp)
        BodyS(text = stringResource(it), color = Colors.White64)
    }
}

/** What the ended force transfer returned to savings: the spending balance and the emergency fees it did not spend. */
@Composable
private fun ReturnedNote(receivedSats: ULong, feesSats: ULong) {
    VerticalSpacer(32.dp)
    val text = if (feesSats > 0uL) {
        stringResource(R.string.lightning__spark_exit__done_returned_fees)
            .replace("{fees}", sparkExitAmountText(feesSats))
    } else {
        stringResource(R.string.lightning__spark_exit__done_returned)
    }
    BodyS(
        text = text.replace("{amount}", sparkExitAmountText(receivedSats)).withAccentBoldBright(),
        color = Colors.White64,
        modifier = Modifier.testTag("SparkExitReturned")
    )
}

@Composable
private fun progressTitle(phase: SparkExitRecord.Phase?, funding: SparkExitFundingState?): String = stringResource(
    when (phase) {
        SparkExitRecord.Phase.Done -> R.string.lightning__spark_exit__done_title
        SparkExitRecord.Phase.Failed(CANCELLED_REASON) -> R.string.lightning__spark_exit__cancelled_title
        is SparkExitRecord.Phase.Failed -> R.string.lightning__spark_exit__failed_title
        // A payment that covers it, such as a higher fee paid from savings, only waits to confirm.
        is SparkExitRecord.Phase.NeedsFunding if funding is SparkExitFundingState.Ask ->
            R.string.lightning__spark_exit__needs_funding_title
        else -> R.string.lightning__spark_exit__progress_title
    },
)

@Composable
private fun progressText(
    record: SparkExitRecord?,
    progressFunding: SparkExitFundingState?,
    staysOnSparkSats: ULong?,
): String = when (record?.phase) {
    SparkExitRecord.Phase.Done -> listOfNotNull(
        stringResource(R.string.lightning__spark_exit__done_text),
        record.failedLeafSats.takeIf { record.isFailedLeavesReleased && it > 0uL }?.let {
            stringResource(R.string.lightning__spark_exit__failed_leaves).replace("{amount}", sparkExitAmountAccent(it))
        },
    ).joinToString(" ")
    SparkExitRecord.Phase.Failed(CANCELLED_REASON) ->
        stringResource(R.string.lightning__spark_exit__cancelled_text)
    is SparkExitRecord.Phase.Failed -> sparkExitFailedText(
        nothingText = stringResource(R.string.lightning__spark_exit__failed_text),
        staysText = stringResource(R.string.lightning__spark_exit__failed_stays),
        outsideAmount = staysOnSparkSats?.let { sparkExitAmountAccent(it) },
    )
    is SparkExitRecord.Phase.NeedsFunding -> when (val funding = progressFunding) {
        // A payment that covers it waits to confirm: nothing to ask for.
        is SparkExitFundingState.Ask -> stringResource(R.string.lightning__spark_exit__needs_funding_text)
            .replace("{amount}", sparkExitSendAmountText(funding.sats))
        else -> stringResource(R.string.lightning__spark_exit__progress_text)
    }
    else -> stringResource(R.string.lightning__spark_exit__progress_text)
}

/**
 * The stopped exit's text: nothing was recovered, and what Spark still holds beyond the exit when it holds any. Lost
 * leaves are gone, so they are not said to stay on Spark.
 */
internal fun sparkExitFailedText(nothingText: String, staysText: String, outsideAmount: String?): String =
    listOfNotNull(nothingText, outsideAmount?.let { staysText.replace("{amount}", it) }).joinToString(" ")

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = SparkExitUiState(
                record = SparkExitRecord(
                    network = "regtest",
                    createdAt = 0L,
                    phase = SparkExitRecord.Phase.Running,
                    feeRateSatPerVbyte = 2uL,
                    destination = "bcrt1q",
                    leafIds = listOf("leaf"),
                    funding = listOf(SparkExitRecord.Funding(0u, "bcrt1qfunding", "02ab")),
                    expectedSats = 91_000uL,
                    claimableAtHeight = 2_100u,
                ),
                tipHeight = 1_900u,
                backgroundLimits = SparkExitBackgroundLimits(areNotificationsEnabled = false),
            ),
        )
    }
}
