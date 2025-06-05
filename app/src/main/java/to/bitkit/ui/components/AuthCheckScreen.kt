package to.bitkit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.navOptions
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

    val isPinOnLaunchEnabled by settings.isPinOnLaunchEnabled.collectAsStateWithLifecycle()
    val isBiometricEnabled by settings.isBiometricEnabled.collectAsStateWithLifecycle()
    val isPinOnIdleEnabled by settings.isPinOnIdleEnabled.collectAsStateWithLifecycle()
    val isPinForPaymentsEnabled by settings.isPinForPaymentsEnabled.collectAsStateWithLifecycle()

    AuthCheckView(
        showLogoOnPin = route.showLogoOnPin,
        appViewModel = app,
        settingsViewModel = settings,
        requireBiometrics = route.requireBiometrics,
        requirePin = route.requirePin,
        onSuccess = {
            when (route.onSuccessActionId) {
                AuthCheckAction.TOGGLE_BIOMETRICS -> {
                    settings.setIsBiometricEnabled(!isBiometricEnabled)
                    navController.popBackStack()
                }

                AuthCheckAction.TOGGLE_PIN_ON_LAUNCH -> {
                    settings.setIsPinOnLaunchEnabled(!isPinOnLaunchEnabled)
                    navController.popBackStack()
                }

                AuthCheckAction.TOGGLE_PIN_ON_IDLE -> {
                    settings.setIsPinOnIdleEnabled(!isPinOnIdleEnabled)
                    navController.popBackStack()
                }

                AuthCheckAction.TOGGLE_PIN_FOR_PAYMENTS -> {
                    settings.setIsPinForPaymentsEnabled(!isPinForPaymentsEnabled)
                    navController.popBackStack()
                }

                AuthCheckAction.DISABLE_PIN -> {
                    app.removePin()
                    navController.popBackStack()
                }

                AuthCheckAction.NAV_TO_RESET -> {
                    navController.navigate(
                        route = Routes.ResetAndRestoreSettings,
                        navOptions = navOptions { popUpTo(Routes.BackupSettings) }
                    )
                }
            }
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
    const val NAV_TO_RESET = "NAV_TO_RESET"
}
