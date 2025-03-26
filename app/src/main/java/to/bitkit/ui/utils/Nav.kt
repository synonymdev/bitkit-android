package to.bitkit.ui.utils

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavOptionsBuilder

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
