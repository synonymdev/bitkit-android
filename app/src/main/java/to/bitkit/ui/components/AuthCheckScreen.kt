package to.bitkit.ui.components

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import to.bitkit.ui.Routes
import to.bitkit.ui.appViewModel

@Composable
fun AuthCheckScreen(
    navController: NavController,
    route: Routes.AuthCheck,
) {
    val app = appViewModel ?: return

    AuthCheckView(
        showLogoOnPin = route.showLogoOnPin,
        appViewModel = app,
        requireBiometrics = route.requireBiometrics,
        requirePin = route.requirePin,
        onSuccess = {
            navController.previousBackStackEntry
                ?.savedStateHandle
                ?.set(AuthCheckAction.KEY, route.onSuccessActionId)

            navController.popBackStack()
        },
    )
}

object AuthCheckAction {
    const val KEY = "auth_check_action_key"

    object Id {
        const val TOGGLE_PIN_ON_LAUNCH = "toggle_pin_on_launch"
        const val TOGGLE_BIOMETRICS = "toggle_biometrics"
        const val DISABLE_PIN = "disable_pin"
    }
}
