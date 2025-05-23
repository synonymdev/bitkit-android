package to.bitkit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.ui.Routes
import to.bitkit.ui.appViewModel
import to.bitkit.ui.settingsViewModel

@Composable
fun AuthCheckScreen(
    navController: NavController,
    route: Routes.AuthCheck,
) {
    val app = appViewModel ?: return
    val settings = settingsViewModel ?: return

    val isPinOnLaunchEnabled by app.isPinOnLaunchEnabled.collectAsStateWithLifecycle()
    val isBiometricEnabled by app.isBiometricEnabled.collectAsStateWithLifecycle()
    val isPinOnIdleEnabled by settings.isPinOnIdleEnabled.collectAsStateWithLifecycle()
    val isPinForPaymentsEnabled by settings.isPinForPaymentsEnabled.collectAsStateWithLifecycle()

    AuthCheckView(
        showLogoOnPin = route.showLogoOnPin,
        appViewModel = app,
        requireBiometrics = route.requireBiometrics,
        requirePin = route.requirePin,
        onSuccess = {
            when (route.onSuccessActionId) {
                AuthCheckAction.TOGGLE_BIOMETRICS -> {
                    app.setIsBiometricEnabled(!isBiometricEnabled)
                }

                AuthCheckAction.TOGGLE_PIN_ON_LAUNCH -> {
                    app.setIsPinOnLaunchEnabled(!isPinOnLaunchEnabled)
                }

                AuthCheckAction.TOGGLE_PIN_ON_IDLE -> {
                    settings.setIsPinOnIdleEnabled(!isPinOnIdleEnabled)
                }

                AuthCheckAction.TOGGLE_PIN_FOR_PAYMENTS -> {
                    settings.setIsPinForPaymentsEnabled(!isPinForPaymentsEnabled)
                }

                AuthCheckAction.DISABLE_PIN -> {
                    app.removePin()
                }
            }

            navController.popBackStack()
        },
        onBack = { navController.popBackStack() },
    )
}

object AuthCheckAction {
    const val TOGGLE_PIN_ON_LAUNCH = "TOGGLE_PIN_ON_LAUNCH"
    const val TOGGLE_BIOMETRICS = "TOGGLE_BIOMETRICS"
    const val TOGGLE_PIN_ON_IDLE = "TOGGLE_PIN_ON_IDLE"
    const val TOGGLE_PIN_FOR_PAYMENTS = "TOGGLE_PIN_FOR_PAYMENTS"
    const val DISABLE_PIN = "DISABLE_PIN"
}
