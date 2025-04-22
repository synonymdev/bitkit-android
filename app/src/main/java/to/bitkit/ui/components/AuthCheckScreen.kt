package to.bitkit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.ui.Routes
import to.bitkit.ui.appViewModel

@Composable
fun AuthCheckScreen(
    navController: NavController,
    route: Routes.AuthCheck,
) {
    val app = appViewModel ?: return

    val isPinOnLaunchEnabled by app.isPinOnLaunchEnabled.collectAsStateWithLifecycle()
    val isBiometricEnabled by app.isBiometricEnabled.collectAsStateWithLifecycle()

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

                AuthCheckAction.DISABLE_PIN -> {
                    app.removePin()
                }
            }

            navController.popBackStack()
        },
    )
}

object AuthCheckAction {
    const val TOGGLE_PIN_ON_LAUNCH = "toggle_pin_on_launch"
    const val TOGGLE_BIOMETRICS = "toggle_biometrics"
    const val DISABLE_PIN = "disable_pin"
}
