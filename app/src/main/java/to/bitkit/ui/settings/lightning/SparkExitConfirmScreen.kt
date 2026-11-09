package to.bitkit.ui.settings.lightning

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.rememberSubscriptionNotificationsRequest
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.utils.withAccentBoldBright

/** The last look before the exit starts: what arrives and for how long the funds are out of reach. */
@Composable
fun SparkExitConfirmScreen(
    viewModel: SparkExitViewModel,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.effects.collect { if (it == SparkExitEffect.Started) onStart() }
    }
    // Asked as the exit starts: the notifications are how a stalled background run reaches the user.
    val requestNotifications = rememberSubscriptionNotificationsRequest()
    Content(
        uiState = uiState,
        onBack = onBack,
        onSwipe = {
            requestNotifications()
            viewModel.start()
        },
    )
}

@Composable
private fun Content(
    uiState: SparkExitUiState,
    onBack: () -> Unit = {},
    onSwipe: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__spark_exit__nav_title),
            onBackClick = onBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            VerticalSpacer(32.dp)
            Display(text = stringResource(R.string.lightning__spark_exit__confirm_title).withAccent())
            VerticalSpacer(8.dp)
            BodyM(
                text = stringResource(R.string.lightning__spark_exit__confirm_text).withAccentBoldBright(),
                color = Colors.White64,
            )
            VerticalSpacer(32.dp)
            uiState.quote?.let { SparkExitQuoteRows(it) }
            uiState.savingsTotalSats?.let { total ->
                uiState.savingsFeeSats?.let { fee -> SparkExitSavingsRows(fee, total) }
            }
            VerticalSpacer(32.dp)
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            SwipeToConfirm(
                text = stringResource(R.string.lightning__spark_exit__swipe),
                color = Colors.Brand,
                loading = uiState.isStarting,
                enabled = uiState.quote != null && !uiState.isStarting,
                onConfirm = onSwipe,
                modifier = Modifier.testTag("SparkExitSwipe")
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(uiState = SparkExitUiState())
    }
}
