package to.bitkit.ui.utils

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import kotlin.reflect.KType

fun NavOptionsBuilder.clearBackStack() = popUpTo(id = 0)

// region transitions

/** enterTransition */
val screenSlideIn = slideInHorizontally(animationSpec = tween(), initialOffsetX = { it })

/** exitTransition */
val screenSlideOut = slideOutHorizontally(animationSpec = tween(), targetOffsetX = { it })

/** popEnterTransition */
val screenScaleIn = scaleIn(animationSpec = tween(), initialScale = 0.95f) + fadeIn()

/** popExitTransition */
val screenScaleOut = scaleOut(animationSpec = tween(), targetScale = 0.95f) + fadeOut()

// endregion

/**
//  * Adds the [androidx.compose.runtime.Composable] to the [androidx.navigation.NavGraphBuilder] with the default screen transitions.
//  */
inline fun <reified T : Any> NavGraphBuilder.composableWithDefaultTransitions(
    typeMap: Map<KType, NavType<*>> = emptyMap(),
    deepLinks: List<NavDeepLink> = emptyList(),
    noinline enterTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = { screenSlideIn },
    noinline exitTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = { screenScaleOut },
    noinline popEnterTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = { screenScaleIn },
    noinline popExitTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = { screenSlideOut },
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable<T>(
        typeMap = typeMap,
        deepLinks = deepLinks,
        enterTransition = enterTransition,
        exitTransition = exitTransition,
        popEnterTransition = popEnterTransition,
        popExitTransition = popExitTransition,
        content = content,
    )
}
