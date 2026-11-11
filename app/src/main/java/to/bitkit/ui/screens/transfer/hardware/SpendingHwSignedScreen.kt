package to.bitkit.ui.screens.transfer.hardware

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import to.bitkit.R
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.HardwareTransferIllustration
import to.bitkit.ui.components.SIGNED_VISUAL_TOP_RATIO
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.transfer.SparkFundingContract.Quote
import to.bitkit.ui.screens.transfer.SparkFundingContract.UiState
import to.bitkit.ui.screens.transfer.SparkFundingViewModel
import to.bitkit.ui.screens.transfer.SpendingFeeGrid
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import kotlin.time.Duration.Companion.seconds

/** How long the signed confirmation shows before the transfer's progress takes over. */
private val SIGNED_AUTO_NAV_DELAY = 1.seconds

@Composable
fun SpendingHwSignedScreen(
    viewModel: SparkFundingViewModel,
    onContinue: () -> Unit,
    onCloseClick: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        delay(SIGNED_AUTO_NAV_DELAY)
        onContinue()
    }

    Content(
        state = state,
        onBackClick = onCloseClick,
    )
}

@Composable
private fun Content(
    state: UiState,
    onBackClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__transfer__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
        Box(modifier = Modifier.fillMaxSize()) {
            HardwareTransferIllustration(
                drawableRes = R.drawable.check,
                topRatio = SIGNED_VISUAL_TOP_RATIO,
            )

            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
                    .testTag("HardwareTransferSigned")
            ) {
                VerticalSpacer(32.dp)
                Display(
                    text = stringResource(R.string.lightning__transfer_hw__signed_title)
                        .withAccent(accentColor = Colors.Purple),
                    modifier = Modifier.fillMaxWidth()
                )
                VerticalSpacer(16.dp)

                SpendingFeeGrid(quote = state.quote, speed = state.speed)
            }
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            state = UiState(
                quote = Quote(
                    address = "bcrt1q",
                    amountSats = 50_000,
                    networkFeeSats = 1_141,
                    instantFeeSats = 1_350,
                    laterFeeSats = 198,
                    spendableSats = 120_000,
                ),
            ),
        )
    }
}
