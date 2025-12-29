package to.bitkit.ui.nav

import androidx.compose.animation.core.AnimationConstants
import androidx.compose.runtime.Stable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

@Stable
class Navigator(@PublishedApi internal val backStack: NavBackStack<NavKey>) {

    fun navigate(route: Routes) {
        if (backStack.lastOrNull() != route) {
            backStack.add(route)
        }
    }

    fun goBack(): Boolean = backStack.removeLastOrNull() != null

    fun popBackTo(route: Routes, inclusive: Boolean = false): Boolean {
        val index = backStack.indexOfFirst { it == route }
        if (index == -1) return false

        val removeCount = if (inclusive) {
            backStack.size - index
        } else {
            backStack.size - index - 1
        }

        repeat(removeCount) {
            backStack.removeLastOrNull()
        }
        return true
    }

    fun navigateToHome() {
        val homeIndex = backStack.indexOfFirst { it is Routes.Home }
        if (homeIndex != -1) {
            while (backStack.size > homeIndex + 1) {
                backStack.removeLastOrNull()
            }
        } else {
            while (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
            if (backStack.lastOrNull() !is Routes.Home) {
                backStack.add(Routes.Home)
            }
        }
    }

    fun isAtHome(): Boolean = backStack.lastOrNull() is Routes.Home

    fun shouldShowTabBar(): Boolean = when (backStack.lastOrNull()) {
        is Routes.Home, is Routes.Savings, is Routes.Spending, is Routes.Activity.All -> true
        else -> false
    }
}

const val MS_NAV_DELAY = 100L
const val MS_TRANSITION_SCREEN = AnimationConstants.DefaultDurationMillis.toLong() // 300ms
