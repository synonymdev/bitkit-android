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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
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
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.utils.withAccentBoldBright

/** The running exit's steps and countdown, and its result once it ends. */
@Composable
fun SparkExitProgressScreen(
    viewModel: SparkExitViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.effects.collect { if (it == SparkExitEffect.Cancelled) onDone() }
    }
    val isEnded = uiState.record?.isActive == false
    LaunchedEffect(isEnded) { if (isEnded) viewModel.onResultShown() }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshBackgroundLimits()
        onPauseOrDispose {}
    }
    Content(
        uiState = uiState,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onCancel = viewModel::cancel,
        onDone = onDone,
        onNotificationSettings = context::openNotificationSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Content(
    uiState: SparkExitUiState,
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onCancel: () -> Unit = {},
    onDone: () -> Unit = {},
    onNotificationSettings: () -> Unit = {},
) {
    val record = uiState.record
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__spark_exit__nav_title),
            onBackClick = onBack,
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
                Display(text = progressTitle(record?.phase).withAccent())
                VerticalSpacer(8.dp)
                BodyM(
                    text = progressText(
                        record = record,
                        progressFunding = uiState.progressFunding,
                        outsideExitSats = uiState.outsideExitSats,
                    ).withAccentBoldBright(),
                    color = Colors.White64,
                )
                VerticalSpacer(24.dp)
                record?.let { ProgressDetails(it, uiState) }
                if (record?.isActive == true) {
                    BackgroundNotes(
                        limits = uiState.backgroundLimits,
                        onNotificationSettings = onNotificationSettings,
                    )
                }
                VerticalSpacer(32.dp)
            }
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            if (uiState.canCancel) {
                SecondaryButton(
                    text = stringResource(R.string.lightning__spark_exit__cancel),
                    onClick = onCancel,
                    testTag = "SparkExitCancel",
                )
                VerticalSpacer(16.dp)
            }
            PrimaryButton(
                text = stringResource(R.string.common__ok),
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("SparkExitProgressOk")
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun ProgressDetails(record: SparkExitRecord, uiState: SparkExitUiState) {
    SparkExitSteps(record = record, tipHeight = uiState.tipHeight)
    val address = record.funding.firstOrNull()?.address
    val funding = uiState.progressFunding
    if (funding != null && address != null) {
        VerticalSpacer(24.dp)
        when (funding) {
            is SparkExitFundingState.Ask -> SparkExitFundingAddress(
                address = address,
                requiredSats = funding.sats,
                seenSats = 0uL,
                partial = funding.partial,
            )
            is SparkExitFundingState.Paid -> SparkExitTxNote(
                text = stringResource(R.string.lightning__spark_exit__fund_paid),
                txid = funding.txid,
                txidRes = R.string.lightning__spark_exit__funding_txid,
                testTag = "SparkExitFundingPaid",
            )
            is SparkExitFundingState.Seen -> SparkExitTxNote(
                text = stringResource(R.string.lightning__spark_exit__fund_pending),
                txid = funding.txid,
                txidRes = R.string.lightning__spark_exit__funding_txid,
                testTag = "SparkExitFundingSeen",
            )
        }
    }
    uiState.rejection?.let {
        VerticalSpacer(16.dp)
        BodyS(text = stringResource(R.string.lightning__spark_exit__errors).replace("{error}", it), color = Colors.Red)
    }
    uiState.outsideExitSats?.let {
        VerticalSpacer(16.dp)
        BodyS(
            text = stringResource(R.string.lightning__spark_exit__outside_exit)
                .replace("{amount}", sparkExitAmountText(it)),
            color = Colors.White64,
        )
    }
    if (record.lostSats > 0uL) {
        VerticalSpacer(16.dp)
        BodyS(
            text = stringResource(R.string.lightning__spark_exit__lost_leaves)
                .replace("{amount}", sparkExitAmountText(record.lostSats)),
            color = Colors.Red,
            modifier = Modifier.testTag("SparkExitLostLeaves")
        )
    }
    if (record.leftBehindLeafSats > 0uL) {
        VerticalSpacer(16.dp)
        BodyS(
            text = stringResource(R.string.lightning__spark_exit__left_behind_leaves)
                .replace("{amount}", sparkExitAmountText(record.leftBehindLeafSats)),
            color = Colors.White64,
            modifier = Modifier.testTag("SparkExitLeftBehindLeaves")
        )
    }
    if (!record.isActive && address != null) LeftoverNote(record, address)
}

/** Where the leftover fee funding of an ended exit is: being sent, sent, kept as dust, or still at its address. */
@Composable
private fun LeftoverNote(record: SparkExitRecord, address: String) {
    if (record.leftover.isEmpty()) return
    val total = record.leftover.fold(0uL) { sum, leftover -> sum + leftover.valueSats }
    val sweep = record.leftoverSweep
    VerticalSpacer(16.dp)
    when {
        sweep != null && sweep.txid == null -> BodyS(
            text = stringResource(R.string.lightning__spark_exit__leftover_dust)
                .replace("{amount}", sparkExitAmountText(total))
                .replace("{address}", address),
            color = Colors.White64,
        )
        sweep?.txid != null && sweep.isAccepted -> SparkExitTxNote(
            text = stringResource(R.string.lightning__spark_exit__leftover_swept)
                .replace("{amount}", sparkExitAmountText(sweep.valueSats)),
            txid = sweep.txid,
            txidRes = R.string.lightning__spark_exit__leftover_txid,
            testTag = "SparkExitLeftoverSwept",
        )
        sweep?.txid != null && sweep.rejection == null -> BodyS(
            text = stringResource(R.string.lightning__spark_exit__leftover_sending)
                .replace("{amount}", sparkExitAmountText(sweep.valueSats)),
            color = Colors.White64,
        )
        else -> BodyS(
            text = stringResource(R.string.lightning__spark_exit__leftover)
                .replace("{amount}", sparkExitAmountText(total))
                .replace("{address}", address),
            color = Colors.White64,
        )
    }
}

@Composable
private fun BackgroundNotes(
    limits: SparkExitBackgroundLimits,
    onNotificationSettings: () -> Unit,
) {
    val notes = listOfNotNull(
        R.string.lightning__spark_exit__note_restricted.takeIf { limits.isRestricted },
        R.string.lightning__spark_exit__note_battery_saver.takeIf { limits.isPowerSaveMode },
        R.string.lightning__spark_exit__note_battery_optimized.takeIf {
            limits.isOptimized && !limits.isRestricted && !limits.isPowerSaveMode
        },
        R.string.lightning__spark_exit__note_notifications.takeIf { !limits.areNotificationsEnabled },
    )
    notes.forEach {
        VerticalSpacer(16.dp)
        BodyS(text = stringResource(it), color = Colors.White64)
    }
    if (!limits.areNotificationsEnabled) {
        VerticalSpacer(16.dp)
        SecondaryButton(
            text = stringResource(R.string.lightning__spark_exit__note_notifications_button),
            onClick = onNotificationSettings,
            testTag = "SparkExitNotificationSettings",
        )
    }
}

@Composable
private fun progressTitle(phase: SparkExitRecord.Phase?): String = stringResource(
    when (phase) {
        SparkExitRecord.Phase.Done -> R.string.lightning__spark_exit__done_title
        is SparkExitRecord.Phase.Failed -> R.string.lightning__spark_exit__failed_title
        is SparkExitRecord.Phase.NeedsFunding -> R.string.lightning__spark_exit__needs_funding_title
        else -> R.string.lightning__spark_exit__progress_title
    },
)

@Composable
private fun progressText(
    record: SparkExitRecord?,
    progressFunding: SparkExitFundingState?,
    outsideExitSats: ULong?,
): String = when (record?.phase) {
    SparkExitRecord.Phase.Done -> listOfNotNull(
        stringResource(R.string.lightning__spark_exit__done_text),
        record.skippedLeafCount.takeIf { it > 0 }?.let {
            stringResource(R.string.lightning__spark_exit__partial_text).replace("{count}", "$it")
        },
        record.failedLeafCount.takeIf { it > 0 }?.let {
            stringResource(R.string.lightning__spark_exit__failed_leaves).replace("{count}", "$it")
        },
    ).joinToString(" ")
    is SparkExitRecord.Phase.Failed -> sparkExitFailedText(
        nothingText = stringResource(R.string.lightning__spark_exit__failed_text),
        staysText = stringResource(R.string.lightning__spark_exit__failed_stays),
        outsideAmount = outsideExitSats?.let { sparkExitAmountText(it) },
    )
    is SparkExitRecord.Phase.NeedsFunding -> when (val funding = progressFunding) {
        // A payment that covers it waits to confirm: nothing to ask for.
        is SparkExitFundingState.Ask -> stringResource(R.string.lightning__spark_exit__needs_funding_text)
            .replace("{amount}", sparkExitAmountText(funding.sats))
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
                backgroundLimits = SparkExitBackgroundLimits(isOptimized = true, areNotificationsEnabled = false),
            ),
        )
    }
}
