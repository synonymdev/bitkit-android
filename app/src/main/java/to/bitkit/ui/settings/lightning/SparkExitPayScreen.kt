package to.bitkit.ui.settings.lightning

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.rememberSubscriptionNotificationsRequest
import to.bitkit.ui.utils.withAccentBoldBright

/**
 * The emergency fees paid from another wallet to the funding address: the force transfer starts on its own once a
 * payment that covers them arrives here; fees already there wait for a tap on Continue.
 */
@Composable
fun SparkExitPayScreen(
    viewModel: SparkExitViewModel,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SparkExitStartedEffect(viewModel, onStart)
    // Asked as the exit starts: the notifications are how a stalled background run reaches the user.
    val requestNotifications = rememberSubscriptionNotificationsRequest()
    LaunchedEffect(Unit) { viewModel.watchFundingAddress() }
    DisposableEffect(Unit) { onDispose { viewModel.stopWatchingFundingAddress() } }
    // Starts once per visit when the funding is covered; a failed start shows its error and waits for Try again.
    LaunchedEffect(uiState.canAutoStartFunding) {
        if (!uiState.canAutoStartFunding) return@LaunchedEffect
        requestNotifications()
        viewModel.startOnPayment()
    }
    Content(
        uiState = uiState,
        onStart = {
            requestNotifications()
            viewModel.startFromPay()
        },
        onBack = onBack,
    )
}

@Composable
private fun Content(
    uiState: SparkExitUiState,
    onStart: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    // A start in flight finishes here, so nothing leaves the screen until it ends.
    BackHandler(enabled = uiState.isStarting) {}
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__spark_exit__nav_title),
            onBackClick = onBack.takeUnless { uiState.isStarting },
            actions = { if (!uiState.isStarting) DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            VerticalSpacer(32.dp)
            Display(text = stringResource(R.string.lightning__spark_exit__pay_title).withExitAccent())
            BodyM(
                text = stringResource(R.string.lightning__spark_exit__pay_text).withAccentBoldBright(),
                color = Colors.White64,
            )
            VerticalSpacer(32.dp)
            SparkExitFeesRow(stringResource(R.string.lightning__spark_exit__pay_amount), uiState.fundingSats)
            uiState.fundingAddress?.let {
                VerticalSpacer(32.dp)
                SparkExitFundingAddress(
                    address = it,
                    requiredSats = uiState.fundingSats,
                    seenSats = uiState.fundingSeenSats,
                    partial = uiState.prestartFunding.partial,
                )
            }
            VerticalSpacer(32.dp)
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            // A start can wait minutes for Spark, so its button stays and spins until it ends.
            if (uiState.canTapStartFunding || uiState.isStarting) {
                PrimaryButton(
                    text = stringResource(R.string.common__try_again),
                    onClick = onStart,
                    isLoading = uiState.isStarting,
                    modifier = Modifier.testTag("SparkExitPayRetry")
                )
                VerticalSpacer(16.dp)
            }
            SecondaryButton(
                text = stringResource(R.string.common__cancel),
                onClick = onBack,
                enabled = !uiState.isStarting,
                testTag = "SparkExitPayCancel",
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(uiState = SparkExitUiState(fundingAddress = "bcrt1qfundingaddress"))
    }
}
