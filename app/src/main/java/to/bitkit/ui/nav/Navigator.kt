package to.bitkit.ui.nav

import androidx.compose.animation.core.AnimationConstants
import androidx.compose.runtime.Stable
import androidx.navigation3.runtime.NavKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Stable
class Navigator(private val _backStack: MutableList<NavKey>) {

    val backStack: ImmutableList<NavKey> get() = _backStack.toImmutableList()

    /** Observable list for NavDisplay */
    val navBackStack: List<NavKey> get() = _backStack

    fun navigate(route: Routes) {
        if (_backStack.lastOrNull() != route) {
            _backStack.add(route)
        }
    }

    fun goBack(): Boolean = _backStack.removeLastOrNull() != null

    fun popBackTo(route: Routes, inclusive: Boolean = false): Boolean {
        val index = _backStack.indexOfFirst { it == route }
        if (index == -1) return false

        val removeCount = if (inclusive) {
            _backStack.size - index
        } else {
            _backStack.size - index - 1
        }

        repeat(removeCount) {
            _backStack.removeLastOrNull()
        }
        return true
    }

    fun navigateToHome() {
        val homeIndex = _backStack.indexOfFirst { it is Routes.Home }
        if (homeIndex != -1) {
            while (_backStack.size > homeIndex + 1) {
                _backStack.removeLastOrNull()
            }
        } else {
            while (_backStack.size > 1) {
                _backStack.removeLastOrNull()
            }
            if (_backStack.lastOrNull() !is Routes.Home) {
                _backStack.add(Routes.Home)
            }
        }
    }

    fun isAtHome(): Boolean = _backStack.lastOrNull() is Routes.Home

    fun navigateAndClearBackstack(route: Routes) {
        _backStack.clear()
        _backStack.add(route)
    }

    fun shouldShowTabBar(): Boolean = when (_backStack.lastOrNull()) {
        is Routes.Home, is Routes.Savings, is Routes.Spending, is Routes.Activity.All -> true
        else -> false
    }
}

const val MS_TRANSITION_SCREEN = AnimationConstants.DefaultDurationMillis.toLong() // 300ms
