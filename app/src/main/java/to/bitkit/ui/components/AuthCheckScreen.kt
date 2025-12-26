package to.bitkit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.ui.nav.Navigator
import to.bitkit.ui.nav.Routes
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.SettingsViewModel

@Composable
fun AuthCheckScreen(
    navigator: Navigator,
    route: Routes.AuthCheck,
    appViewModel: AppViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val isPinOnLaunchEnabled by settingsViewModel.isPinOnLaunchEnabled.collectAsStateWithLifecycle()
    val isBiometricEnabled by settingsViewModel.isBiometricEnabled.collectAsStateWithLifecycle()
    val isPinOnIdleEnabled by settingsViewModel.isPinOnIdleEnabled.collectAsStateWithLifecycle()
    val isPinForPaymentsEnabled by settingsViewModel.isPinForPaymentsEnabled.collectAsStateWithLifecycle()

    AuthCheckView(
        showLogoOnPin = route.showLogoOnPin,
        appViewModel = appViewModel,
        settingsViewModel = settingsViewModel,
        requireBiometrics = route.requireBiometrics,
        requirePin = route.requirePin,
        onSuccess = {
            when (route.onSuccessActionId) {
                AuthCheckAction.TOGGLE_BIOMETRICS -> {
                    settingsViewModel.setIsBiometricEnabled(!isBiometricEnabled)
                    navigator.goBack()
                }

                AuthCheckAction.TOGGLE_PIN_ON_LAUNCH -> {
                    settingsViewModel.setIsPinOnLaunchEnabled(!isPinOnLaunchEnabled)
                    navigator.goBack()
                }

                AuthCheckAction.TOGGLE_PIN_ON_IDLE -> {
                    settingsViewModel.setIsPinOnIdleEnabled(!isPinOnIdleEnabled)
                    navigator.goBack()
                }

                AuthCheckAction.TOGGLE_PIN_FOR_PAYMENTS -> {
                    settingsViewModel.setIsPinForPaymentsEnabled(!isPinForPaymentsEnabled)
                    navigator.goBack()
                }

                AuthCheckAction.DISABLE_PIN -> {
                    appViewModel.removePin()
                    navigator.goBack()
                }

                AuthCheckAction.NAV_TO_RESET -> {
                    navigator.navigate(Routes.Settings.ResetAndRestore)
                }
            }
        },
        onBack = { navigator.goBack() },
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
