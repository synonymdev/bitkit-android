package to.bitkit.ui.sheets.hardware

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.viewmodels.AppViewModel

/**
 * Entry point for the hardware-wallet connect flow opened from the home suggestion card,
 * and host of the Pair Device screen shown app-wide when the device asks for its one-time pairing code.
 */
@Composable
fun HardwareSheet(
    sheet: Sheet.Hardware,
    appViewModel: AppViewModel,
) {
    Content(
        sheet = sheet,
        onDismiss = appViewModel::hideSheet,
        onSubmitPairingCode = appViewModel::submitPairingCode,
        onCancelPairingCode = appViewModel::cancelPairingCode,
    )
}

@Composable
private fun Content(
    sheet: Sheet.Hardware,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
    onSubmitPairingCode: (String) -> Unit = {},
    onCancelPairingCode: () -> Unit = {},
) {
    val navController = rememberNavController()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .sheetHeight(SheetSize.LARGE)
            .testTag("hardware_sheet")
    ) {
        NavHost(
            navController = navController,
            startDestination = sheet.route,
        ) {
            composableWithDefaultTransitions<HardwareRoute.Intro> {
                HwIntroSheet(onDismiss = onDismiss)
            }
            composableWithDefaultTransitions<HardwareRoute.PairCode> {
                HwPairCodeSheet(
                    onSubmit = onSubmitPairingCode,
                    onCancel = onCancelPairingCode,
                )
            }
        }
    }
}

sealed interface HardwareRoute {
    @Serializable
    data object Intro : HardwareRoute

    @Serializable
    data object PairCode : HardwareRoute
}
