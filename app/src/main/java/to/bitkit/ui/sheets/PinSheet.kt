package to.bitkit.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.navigateTo
import to.bitkit.ui.settings.pin.PinBiometricsScreen
import to.bitkit.ui.settings.pin.PinChooseScreen
import to.bitkit.ui.settings.pin.PinConfirmScreen
import to.bitkit.ui.settings.pin.PinPromptScreen
import to.bitkit.ui.settings.pin.PinResultScreen
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.viewmodels.AppViewModel

@Composable
fun PinSheet(
    sheet: Sheet.Pin,
    app: AppViewModel,
) {
    val navController = rememberNavController()
    val onDismiss = app::hideSheet

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sheetHeight(SheetSize.MEDIUM)
    ) {
        NavHost(
            navController = navController,
            startDestination = sheet.route,
        ) {
            composableWithDefaultTransitions<PinRoute.Prompt> {
                PinPromptScreen(
                    showLaterButton = it.toRoute<PinRoute.Prompt>().showLaterButton,
                    onContinue = { navController.navigateTo(PinRoute.Choose) },
                    onLater = onDismiss,
                )
            }
            composableWithDefaultTransitions<PinRoute.Choose> {
                PinChooseScreen(
                    onPinChosen = { pin ->
                        navController.navigateTo(PinRoute.Confirm(pin))
                    },
                    onBack = { if (!navController.popBackStack()) onDismiss() },
                )
            }
            composableWithDefaultTransitions<PinRoute.Confirm> {
                PinConfirmScreen(
                    originalPin = it.toRoute<PinRoute.Confirm>().pin,
                    onPinConfirmed = { navController.navigateTo(PinRoute.Biometrics) },
                    onBack = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<PinRoute.Biometrics> {
                PinBiometricsScreen(
                    onContinue = { isBioOn ->
                        navController.navigateTo(PinRoute.Result(isBioOn))
                    },
                    onSkip = { navController.navigateTo(PinRoute.Result(isBioOn = false)) },
                    onBack = onDismiss,
                )
            }
            composableWithDefaultTransitions<PinRoute.Result> {
                PinResultScreen(
                    isBioOn = it.toRoute<PinRoute.Result>().isBioOn,
                    onDismiss = onDismiss,
                    onBack = onDismiss,
                )
            }
        }
    }
}

sealed interface PinRoute {
    @Serializable
    data class Prompt(val showLaterButton: Boolean = false) : PinRoute

    @Serializable
    data object Choose : PinRoute

    @Serializable
    data class Confirm(val pin: String) : PinRoute

    @Serializable
    data object Biometrics : PinRoute

    @Serializable
    data class Result(val isBioOn: Boolean) : PinRoute
}
