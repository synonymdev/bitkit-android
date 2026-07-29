package to.bitkit.ui.utils

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import to.bitkit.ui.utils.Transitions.defaultEnterTrans
import to.bitkit.ui.utils.Transitions.defaultExitTrans
import to.bitkit.ui.utils.Transitions.defaultPopEnterTrans
import to.bitkit.ui.utils.Transitions.defaultPopExitTrans
import kotlin.reflect.KType

@Suppress("MagicNumber")
object Transitions {
    val slideInHorizontally = slideInHorizontally(animationSpec = tween(), initialOffsetX = { it })
    val slideOutHorizontally = slideOutHorizontally(animationSpec = tween(), targetOffsetX = { it })
    val slideInVertically = slideInVertically(animationSpec = tween(), initialOffsetY = { it })
    val slideOutVertically = slideOutVertically(animationSpec = tween(), targetOffsetY = { it })

    val defaultEnterTrans: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?) = {
        slideInHorizontally(
            initialOffsetX = { fullWidth -> fullWidth },
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        )
    }

    val defaultExitTrans: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?) = {
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> -fullWidth / 3 },
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        ) + fadeOut(
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            targetAlpha = 0.8f
        )
    }

    val defaultPopEnterTrans: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?) = {
        slideInHorizontally(
            initialOffsetX = { fullWidth -> -fullWidth / 3 },
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        ) + fadeIn(
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            initialAlpha = 0.8f
        )
    }

    val defaultPopExitTrans: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?) = {
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> fullWidth },
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        )
    }
}

/**
 * Construct a nested [NavGraph] with the default screen transitions.
 */
@Suppress("LongParameterList", "MaxLineLength")
inline fun <reified T : Any> NavGraphBuilder.navigationWithDefaultTransitions(
    startDestination: Any,
    typeMap: Map<KType, @JvmSuppressWildcards NavType<*>> = emptyMap(),
    deepLinks: List<NavDeepLink> = ScreenDeepLinks.linksFor(T::class),
    noinline enterTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = defaultEnterTrans,
    noinline exitTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = defaultExitTrans,
    noinline popEnterTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = defaultPopEnterTrans,
    noinline popExitTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = defaultPopExitTrans,
    noinline builder: NavGraphBuilder.() -> Unit,
) {
    navigation<T>(
        startDestination = startDestination,
        typeMap = typeMap,
        deepLinks = deepLinks,
        enterTransition = enterTransition,
        exitTransition = exitTransition,
        popEnterTransition = popEnterTransition,
        popExitTransition = popExitTransition,
        builder = builder,
    )
}

/**
 * Adds the [Composable] to the [NavGraphBuilder] with the default screen transitions.
 */
@Suppress("LongParameterList", "MaxLineLength")
inline fun <reified T : Any> NavGraphBuilder.composableWithDefaultTransitions(
    typeMap: Map<KType, NavType<*>> = emptyMap(),
    deepLinks: List<NavDeepLink> = ScreenDeepLinks.linksFor(T::class),
    noinline enterTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = defaultEnterTrans,
    noinline exitTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = defaultExitTrans,
    noinline popEnterTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = defaultPopEnterTrans,
    noinline popExitTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = defaultPopExitTrans,
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
