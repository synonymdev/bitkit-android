package to.bitkit.ui.sheets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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

/**
 * Entry point for the hardware-wallet connect flow opened from the home suggestion
 * card, and host of the Pair Device screen shown app-wide when the device asks for
 * its one-time pairing code. The remaining connect steps land in the dedicated
 * connect-flow subtask, which enables the Continue button.
 */
@Composable
fun HardwareSheet(
    sheet: Sheet.Hardware,
    onDismiss: () -> Unit,
    onSubmitPairingCode: (String) -> Unit,
    onCancelPairingCode: () -> Unit,
) {
    val navController = rememberNavController()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .sheetHeight(SheetSize.LARGE),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("hardware_sheet"),
        ) {
            NavHost(
                navController = navController,
                startDestination = sheet.route,
            ) {
                composableWithDefaultTransitions<HardwareRoute.Intro> {
                    HwIntroSheet(onDismiss = onDismiss)
                }
                composableWithDefaultTransitions<HardwareRoute.PairingCode> {
                    HwPairSheet(
                        onSubmit = onSubmitPairingCode,
                        onCancel = onCancelPairingCode,
                    )
                }
            }
        }
    }
}

sealed interface HardwareRoute {
    @Serializable
    data object Intro : HardwareRoute

    @Serializable
    data object PairingCode : HardwareRoute
}
