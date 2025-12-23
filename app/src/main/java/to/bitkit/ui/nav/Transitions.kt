package to.bitkit.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
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
import androidx.compose.animation.togetherWith
import androidx.navigation3.ui.NavDisplay

private const val MS_DURATION = 300

object Transitions {

    val screenDefault: AnimatedContentTransitionScope<*>.() -> ContentTransform = {
        slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = tween(MS_DURATION, easing = FastOutSlowInEasing)
        ) togetherWith slideOutHorizontally(
            targetOffsetX = { -it / 3 },
            animationSpec = tween(MS_DURATION, easing = FastOutSlowInEasing)
        ) + fadeOut(
            animationSpec = tween(MS_DURATION, easing = FastOutSlowInEasing),
            targetAlpha = 0.8f
        )
    }

    val screenDefaultPop: AnimatedContentTransitionScope<*>.() -> ContentTransform = {
        slideInHorizontally(
            initialOffsetX = { -it / 3 },
            animationSpec = tween(MS_DURATION, easing = FastOutSlowInEasing)
        ) + fadeIn(
            animationSpec = tween(MS_DURATION, easing = FastOutSlowInEasing),
            initialAlpha = 0.8f
        ) togetherWith slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = tween(MS_DURATION, easing = FastOutSlowInEasing)
        )
    }

    val screenDefaultPredictivePop: AnimatedContentTransitionScope<*>.(Int) -> ContentTransform = { _ ->
        slideInHorizontally(
            initialOffsetX = { -it / 3 },
            animationSpec = tween(MS_DURATION, easing = FastOutSlowInEasing)
        ) + fadeIn(
            animationSpec = tween(MS_DURATION, easing = FastOutSlowInEasing),
            initialAlpha = 0.8f
        ) togetherWith slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = tween(MS_DURATION, easing = FastOutSlowInEasing)
        )
    }

    private val verticalSlideSpec = NavDisplay.transitionSpec {
        slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(MS_DURATION)
        ) togetherWith ExitTransition.KeepUntilTransitionsFinished
    }

    private val verticalSlidePopSpec = NavDisplay.popTransitionSpec {
        EnterTransition.None togetherWith slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(MS_DURATION)
        )
    }

    private val verticalSlidePredictivePopSpec = NavDisplay.predictivePopTransitionSpec {
        EnterTransition.None togetherWith slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(MS_DURATION)
        )
    }

    val verticalSlideMetadata = verticalSlideSpec + verticalSlidePopSpec + verticalSlidePredictivePopSpec
}
