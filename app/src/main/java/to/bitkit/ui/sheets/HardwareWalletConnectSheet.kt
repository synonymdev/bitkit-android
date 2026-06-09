package to.bitkit.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.composableWithDefaultTransitions

/**
 * Entry point for the hardware-wallet connect flow opened from the home suggestion
 * card. The multi-screen scaffolding (nav host + typed routes) is in place; the four
 * real steps land in the dedicated connect-flow subtask.
 */
@Composable
fun HardwareWalletConnectSheet(
    sheet: Sheet.HardwareWalletConnect,
    onDismiss: () -> Unit,
) {
    val navController = rememberNavController()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sheetHeight(SheetSize.MEDIUM)
            .testTag("hardware_wallet_connect_sheet")
    ) {
        NavHost(
            navController = navController,
            startDestination = sheet.route,
        ) {
            composableWithDefaultTransitions<HwConnectRoute.Intro> {
                HardwareWalletConnectIntro(onClose = onDismiss)
            }
        }
    }
}

@Composable
private fun HardwareWalletConnectIntro(onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SheetTopBar(titleText = "Connect Hardware", onBack = onClose)
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            BodyM(text = "Hardware wallet connect flow is not yet implemented.", color = Colors.White64)
            VerticalSpacer(24.dp)
            PrimaryButton(text = "Close", onClick = onClose)
            VerticalSpacer(16.dp)
        }
    }
}

sealed interface HwConnectRoute {
    @Serializable
    data object Intro : HwConnectRoute
}
