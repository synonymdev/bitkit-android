package to.bitkit.ui.settings.lightning

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.rememberSubscriptionNotificationsRequest

/** The last look before the force transfer starts: what reaches savings and what savings pays for it. */
@Composable
fun SparkExitConfirmScreen(
    viewModel: SparkExitViewModel,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SparkExitStartedEffect(viewModel, onStart)
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
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            VerticalSpacer(32.dp)
            Display(text = stringResource(R.string.lightning__spark_exit__confirm_title).withExitAccent())
            VerticalSpacer(16.dp)
            uiState.quote?.let { SparkExitQuoteRows(it) }
            uiState.savingsTotalSats?.let {
                VerticalSpacer(16.dp)
                SparkExitFeesRow(stringResource(R.string.lightning__spark_exit__fees_savings), it)
            }
            VerticalSpacer(32.dp)
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            SwipeToConfirm(
                text = stringResource(R.string.lightning__spark_exit__swipe),
                color = Colors.Yellow,
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
