// Copied from app/src/main/java/to/bitkit/ui/shared/animations/BalanceAnimations.kt; keep both in sync.
package to.bitkit.wear.ui.animations

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

/** How much the small row scales while it enters or leaves in a unit swap, as on the phone. */
const val SMALL_ROW_SWAP_SCALE = 1.5f

/** How much the large row scales while it enters or leaves in a unit swap, as on the phone. */
const val LARGE_ROW_SWAP_SCALE = 0.5f

/** The balance's unit swap transitions, the same as the phone's and iOS's. */
object BalanceAnimations {
    // Matches iOS: Animation.spring(response: 0.3, dampingFraction: 0.8)
    private val swapSpring = spring<IntOffset>(
        dampingRatio = 0.8f,
        stiffness = Spring.StiffnessMediumLow,
    )
    private val swapFadeSpring = spring<Float>(
        dampingRatio = 0.8f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** The small row (top) enters from below and exits to below. */
    val swapSmallRowTransition: ContentTransform =
        slideInVertically(
            initialOffsetY = { it },
            animationSpec = swapSpring,
        ) + fadeIn(
            animationSpec = swapFadeSpring,
        ) togetherWith slideOutVertically(
            targetOffsetY = { it },
            animationSpec = swapSpring,
        ) + fadeOut(
            animationSpec = swapFadeSpring,
        )

    /** The large row (bottom) enters from above and exits to above. */
    val swapLargeRowTransition: ContentTransform =
        slideInVertically(
            initialOffsetY = { -it },
            animationSpec = swapSpring,
        ) + fadeIn(
            animationSpec = swapFadeSpring,
        ) togetherWith slideOutVertically(
            targetOffsetY = { -it },
            animationSpec = swapSpring,
        ) + fadeOut(
            animationSpec = swapFadeSpring,
        )
}
