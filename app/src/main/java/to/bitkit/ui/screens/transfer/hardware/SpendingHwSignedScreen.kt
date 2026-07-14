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
import com.synonym.bitkitcore.IBtOrder
import kotlinx.coroutines.delay
import to.bitkit.R
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.HardwareTransferIllustration
import to.bitkit.ui.components.SIGNED_VISUAL_TOP_RATIO
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.transfer.previewBtOrder
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.TransferViewModel

/** Figma handoff delay before forwarding from signed confirmation. */
private const val SIGNED_AUTO_NAV_DELAY_MS = 1_000L

@Composable
fun SpendingHwSignedScreen(
    viewModel: TransferViewModel,
    onContinue: () -> Unit,
    onCloseClick: () -> Unit,
) {
    val state by viewModel.spendingUiState.collectAsStateWithLifecycle()

    val order = state.order ?: run {
        onCloseClick()
        return
    }

    LaunchedEffect(Unit) {
        delay(SIGNED_AUTO_NAV_DELAY_MS)
        onContinue()
    }

    Content(
        order = order,
        miningFeeSats = state.hwMiningFeeSats,
        onBackClick = onCloseClick,
    )
}

@Composable
private fun Content(
    order: IBtOrder,
    miningFeeSats: ULong = 0uL,
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

                SpendingHwFeeGrid(
                    order = order,
                    miningFeeSats = miningFeeSats,
                )
            }
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            order = previewBtOrder(),
        )
    }
}
