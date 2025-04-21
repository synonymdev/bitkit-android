package to.bitkit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    var isAuthenticated by rememberSaveable { mutableStateOf(false) }

    if (isAuthenticated) {
        val isPinOnLaunchEnabled by app.isPinOnLaunchEnabled.collectAsStateWithLifecycle()
        val isBiometricEnabled by app.isBiometricEnabled.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
            when (route.onSuccessAction) {
                AuthCheckAction.TOGGLE_PIN_ON_LAUNCH -> app.setIsPinOnLaunchEnabled(!isPinOnLaunchEnabled)
                AuthCheckAction.TOGGLE_BIOMETRICS -> app.setIsBiometricEnabled(!isBiometricEnabled)
            }
            navController.popBackStack()
        }
    } else {
        AuthCheckView(
            showLogoOnPin = route.showLogoOnPin,
            appViewModel = app,
            requireBiometrics = route.requireBiometrics,
            requirePin = route.requirePin,
            onSuccess = { isAuthenticated = true },
        )
    }
}

object AuthCheckAction {
    const val TOGGLE_PIN_ON_LAUNCH = "toggle_pin_on_launch"
    const val TOGGLE_BIOMETRICS = "toggle_biometrics"
}
