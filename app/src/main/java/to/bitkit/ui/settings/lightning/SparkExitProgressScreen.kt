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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.repositories.SparkExitRecord
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
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
    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.effects.collect { if (it == SparkExitEffect.Cancelled) onDone() }
    }
    Content(
        uiState = uiState,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onCancel = viewModel::cancel,
        onDone = onDone,
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
            modifier = Modifier.fillMaxSize()
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
                BodyM(text = progressText(record).withAccentBoldBright(), color = Colors.White64)
                VerticalSpacer(24.dp)
                record?.let { ProgressDetails(it, uiState) }
                FillHeight()
                VerticalSpacer(32.dp)
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
}

@Composable
private fun ProgressDetails(record: SparkExitRecord, uiState: SparkExitUiState) {
    SparkExitSteps(record = record, tipHeight = uiState.tipHeight)
    val address = record.funding.firstOrNull()?.address
    val shortfall = record.fundingShortfallSats
    if (shortfall != null && address != null) {
        VerticalSpacer(24.dp)
        SparkExitFundingAddress(address = address, requiredSats = shortfall, seenSats = uiState.fundingSeenSats)
    }
    uiState.rejection?.let {
        VerticalSpacer(16.dp)
        BodyS(text = stringResource(R.string.lightning__spark_exit__errors).replace("{error}", it), color = Colors.Red)
    }
    if (record.phase == SparkExitRecord.Phase.Done && address != null) {
        record.leftover.forEach {
            VerticalSpacer(16.dp)
            BodyS(
                text = stringResource(R.string.lightning__spark_exit__leftover)
                    .replace("{amount}", sparkExitAmountText(it.valueSats))
                    .replace("{address}", address),
                color = Colors.White64,
            )
        }
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
private fun progressText(record: SparkExitRecord?): String = when (record?.phase) {
    SparkExitRecord.Phase.Done -> listOfNotNull(
        stringResource(R.string.lightning__spark_exit__done_text),
        record.skippedLeafCount.takeIf { it > 0 }?.let {
            stringResource(R.string.lightning__spark_exit__partial_text).replace("{count}", "$it")
        },
        record.failedLeafCount.takeIf { it > 0 }?.let {
            stringResource(R.string.lightning__spark_exit__failed_leaves).replace("{count}", "$it")
        },
    ).joinToString(" ")
    is SparkExitRecord.Phase.Failed -> stringResource(R.string.lightning__spark_exit__failed_text)
    is SparkExitRecord.Phase.NeedsFunding -> stringResource(R.string.lightning__spark_exit__needs_funding_text)
        .replace("{amount}", sparkExitAmountText(record.fundingShortfallSats ?: 0uL))
    else -> stringResource(R.string.lightning__spark_exit__progress_text)
}

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
            ),
        )
    }
}
