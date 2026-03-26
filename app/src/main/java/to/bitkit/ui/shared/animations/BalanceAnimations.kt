package to.bitkit.ui.shared.animations

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

/**
 * Animation specifications for balance hiding/showing transitions.
 * Provides consistent morphing animations across all balance-related components.
 */
object BalanceAnimations {

    /**
     * Main balance header transition (large amounts)
     * Synced with secondary but refined timing for primary hierarchy
     */
    val mainBalanceTransition: ContentTransform = slideInHorizontally(
        initialOffsetX = { if (it > 0) -it / 4 else it / 4 },
        animationSpec = tween(380, easing = EaseInOutCubic)
    ) + fadeIn(
        animationSpec = tween(380, easing = EaseInOutCubic)
    ) togetherWith slideOutHorizontally(
        targetOffsetX = { if (it > 0) it / 4 else -it / 4 },
        animationSpec = tween(380, easing = EaseInOutCubic)
    ) + fadeOut(
        animationSpec = tween(380, easing = EaseInOutCubic)
    )

    /**
     * Secondary balance transition (small amounts)
     * Faster than main balance for hierarchy
     */
    val secondaryBalanceTransition: ContentTransform = slideInHorizontally(
        initialOffsetX = { if (it > 0) -it / 3 else it / 3 },
        animationSpec = tween(400)
    ) + fadeIn(animationSpec = tween(400)) togetherWith
        slideOutHorizontally(
            targetOffsetX = { if (it > 0) it / 3 else -it / 3 },
            animationSpec = tween(400)
        ) + fadeOut(animationSpec = tween(400))

    /**
     * Activity list amount transition
     * Optimized for scrolling performance
     */
    val activityAmountTransition: ContentTransform = slideInHorizontally(
        initialOffsetX = { if (it > 0) it / 3 else -it / 3 },
        animationSpec = tween(350)
    ) + fadeIn(animationSpec = tween(350)) togetherWith
        slideOutHorizontally(
            targetOffsetX = { if (it > 0) -it / 3 else it / 3 },
            animationSpec = tween(350)
        ) + fadeOut(animationSpec = tween(350))

    /**
     * Activity list subtitle transition
     * Staggered timing with main amount
     */
    val activitySubtitleTransition: ContentTransform = slideInHorizontally(
        initialOffsetX = { if (it > 0) it / 4 else -it / 4 },
        animationSpec = tween(300, delayMillis = 50)
    ) + fadeIn(animationSpec = tween(300, delayMillis = 50)) togetherWith
        slideOutHorizontally(
            targetOffsetX = { if (it > 0) -it / 4 else it / 4 },
            animationSpec = tween(300)
        ) + fadeOut(animationSpec = tween(300))

    /**
     * Wallet balance view transition (savings/spending)
     * Balanced timing for home screen
     */
    val walletBalanceTransition: ContentTransform = slideInHorizontally(
        initialOffsetX = { if (it > 0) -it / 3 else it / 3 },
        animationSpec = tween(380)
    ) + fadeIn(animationSpec = tween(380)) togetherWith
        slideOutHorizontally(
            targetOffsetX = { if (it > 0) it / 3 else -it / 3 },
            animationSpec = tween(380)
        ) + fadeOut(animationSpec = tween(380))

    // Matches iOS: Animation.spring(response: 0.3, dampingFraction: 0.8)
    private val swapSpring = spring<IntOffset>(
        dampingRatio = 0.8f,
        stiffness = Spring.StiffnessMediumLow,
    )
    private val swapFadeSpring = spring<Float>(
        dampingRatio = 0.8f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /**
     * Swap transition for the small row (top).
     * Matches iOS: .move(edge: .bottom) + .opacity + .scale(1.5, anchor: .topLeading)
     * Enter from below, exit to below, with spring physics.
     */
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

    /**
     * Swap transition for the large row (bottom).
     * Matches iOS: .move(edge: .top) + .opacity + .scale(0.5, anchor: .topLeading)
     * Enter from above, exit to above, with spring physics.
     */
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

    /**
     * Eye icon transition
     * Simple fade for clean appearance/disappearance
     */
    val eyeIconTransition: ContentTransform = fadeIn(
        animationSpec = tween(300, delayMillis = 100)
    ) togetherWith fadeOut(
        animationSpec = tween(200)
    )
}
