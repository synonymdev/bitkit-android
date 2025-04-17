package to.bitkit.ui.settings.pin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.SheetHost

@Composable
fun PinNavigationSheet(
    showSheet: Boolean,
    showLaterButton: Boolean = true,
    onDismiss: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val app = appViewModel ?: return
    val navController = rememberNavController()

    SheetHost(
        shouldExpand = showSheet,
        onDismiss = {
            navController.popBackStack(PinRoute.PinPrompt, inclusive = false)
            onDismiss()
        },
        sheets = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(.725f)
            ) {
                NavHost(
                    navController = navController,
                    startDestination = PinRoute.PinPrompt
                ) {
                    composable<PinRoute.PinPrompt> {
                        PinPromptScreen(
                            showLaterButton = showLaterButton,
                            onContinue = { navController.navigate(PinRoute.ChoosePin) },
                            onLater = onDismiss,
                        )
                    }
                    composable<PinRoute.ChoosePin> {
                        ChoosePinScreen(
                            onPinChosen = { pin ->
                                navController.navigate(PinRoute.ConfirmPin(pin))
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable<PinRoute.ConfirmPin> { backStackEntry ->
                        val route = backStackEntry.toRoute<PinRoute.ConfirmPin>()
                        ConfirmPinScreen(
                            originalPin = route.pin,
                            onPinConfirmed = { pin ->
                                // TODO nav to result screen
                                app.addPin(pin)
                                navController.navigate(PinRoute.AskForBiometrics)
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable<PinRoute.AskForBiometrics> {
                        AskForBiometricsScreen(
                            onContinue = { onDismiss() }, // TODO: logic if needed
                            onSkip = onDismiss,
                        )
                    }
                }
            }
        },
        content = content,
    )
}

@Serializable
sealed class PinRoute {
    @Serializable
    data object PinPrompt : PinRoute()

    @Serializable
    data object ChoosePin : PinRoute()

    @Serializable
    data class ConfirmPin(val pin: String) : PinRoute()

    @Serializable
    data object AskForBiometrics : PinRoute()
}
