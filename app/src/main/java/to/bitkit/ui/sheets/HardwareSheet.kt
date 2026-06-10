package to.bitkit.ui.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.ui.utils.withAccent

/**
 * Entry point for the hardware-wallet connect flow opened from the home suggestion
 * card. The intro step is final; the remaining connect steps land in the dedicated
 * connect-flow subtask, which enables the Continue button.
 */
@Composable
fun HardwareSheet(
    sheet: Sheet.Hardware,
    onDismiss: () -> Unit,
) {
    val navController = rememberNavController()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sheetHeight(SheetSize.LARGE)
            .gradientBackground()
            .testTag("hardware_sheet")
    ) {
        NavHost(
            navController = navController,
            startDestination = sheet.route,
        ) {
            composableWithDefaultTransitions<HardwareRoute.Intro> {
                HardwareIntro(onClose = onDismiss)
            }
        }
    }
}

@Composable
private fun HardwareIntro(onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        SheetTopBar(titleText = stringResource(R.string.hardware__intro_title), onBack = onClose)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Image(
                painter = painterResource(R.drawable.trezor),
                contentDescription = null,
                modifier = Modifier
                    .size(256.dp)
                    .align(Alignment.TopStart)
                    .offset(x = (-84).dp, y = 24.dp)
            )
            Image(
                painter = painterResource(R.drawable.ledger),
                contentDescription = null,
                modifier = Modifier
                    .size(256.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 53.dp)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            Display(stringResource(R.string.hardware__intro_header).withAccent(accentColor = Colors.Blue))
            VerticalSpacer(8.dp)
            BodyM(stringResource(R.string.hardware__intro_text), color = Colors.White64)
            VerticalSpacer(32.dp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SecondaryButton(
                    text = stringResource(R.string.common__cancel),
                    onClick = onClose,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = stringResource(R.string.common__continue),
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.weight(1f)
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

sealed interface HardwareRoute {
    @Serializable
    data object Intro : HardwareRoute
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .sheetHeight(SheetSize.LARGE, isModal = true)
                    .gradientBackground()
            ) {
                HardwareIntro(onClose = {})
            }
        }
    }
}
