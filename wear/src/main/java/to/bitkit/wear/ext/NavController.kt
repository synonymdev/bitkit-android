package to.bitkit.wear.ext

import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder

/**
 * Navigates to [route] with [launchSingleTop] always enabled to prevent
 * duplicate destinations on the back stack (e.g. from double-taps).
 */
fun NavController.navigateTo(
    route: String,
    builder: NavOptionsBuilder.() -> Unit = {},
) {
    navigate(route) {
        builder()
        launchSingleTop = true
    }
}
