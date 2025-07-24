package to.bitkit.ui.settings.pin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.utils.composableWithDefaultTransitions

@Composable
fun PinNavigationSheet(
    showLaterButton: Boolean = true,
    onDismiss: () -> Unit,
) {
    val navController = rememberNavController()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(SheetSize.MEDIUM)
    ) {
        NavHost(
            navController = navController,
            startDestination = PinRoute.PinPrompt,
        ) {
            composableWithDefaultTransitions<PinRoute.PinPrompt> {
                PinPromptScreen(
                    showLaterButton = showLaterButton,
                    onContinue = { navController.navigate(PinRoute.ChoosePin) },
                    onLater = onDismiss,
                )
            }
            composableWithDefaultTransitions<PinRoute.ChoosePin> {
                ChoosePinScreen(
                    onPinChosen = { pin ->
                        navController.navigate(PinRoute.ConfirmPin(pin))
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<PinRoute.ConfirmPin> {
                val route = it.toRoute<PinRoute.ConfirmPin>()
                ConfirmPinScreen(
                    originalPin = route.pin,
                    onPinConfirmed = { navController.navigate(PinRoute.AskForBiometrics) },
                    onBack = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<PinRoute.AskForBiometrics> {
                AskForBiometricsScreen(
                    onContinue = { isBioOn ->
                        navController.navigate(PinRoute.Result(isBioOn))
                    },
                    onSkip = { navController.navigate(PinRoute.Result(isBioOn = false)) },
                    onBack = onDismiss,
                )
            }
            composableWithDefaultTransitions<PinRoute.Result> {
                val route = it.toRoute<PinRoute.Result>()
                PinResultScreen(
                    isBioOn = route.isBioOn,
                    onDismiss = onDismiss,
                    onBack = onDismiss,
                )
            }
        }
    }
}

object PinRoute {
    @Serializable
    data object PinPrompt

    @Serializable
    data object ChoosePin

    @Serializable
    data class ConfirmPin(val pin: String)

    @Serializable
    data object AskForBiometrics

    @Serializable
    data class Result(val isBioOn: Boolean)
}
