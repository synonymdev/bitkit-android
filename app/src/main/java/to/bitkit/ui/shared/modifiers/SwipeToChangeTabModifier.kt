package to.bitkit.ui.shared.modifiers

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Enables tab navigation via horizontal swipe gestures.
 *
 * Detects horizontal swipe using both velocity and drag distance to provide
 * iOS-like swipe sensitivity. Navigates to adjacent tabs when either:
 * - Velocity exceeds threshold (for quick flicks)
 * - Drag distance exceeds threshold (for slower, deliberate swipes)
 *
 * @param currentTabIndex The currently selected tab index (0-based)
 * @param tabCount Total number of tabs available for navigation
 * @param onTabChange Callback invoked when user swipes to change tabs, receives the new tab index
 * @param wrapAround Whether a swipe past the first or last tab selects the tab at the other end
 */
fun Modifier.swipeToChangeTab(
    currentTabIndex: Int,
    tabCount: Int,
    onTabChange: (Int) -> Unit,
    wrapAround: Boolean = false,
): Modifier = composed {
    val velocityTracker = remember { VelocityTracker() }
    var totalDragDistance by remember { mutableFloatStateOf(0f) }
    val distanceThresholdPx = with(LocalDensity.current) { DEFAULT_DISTANCE_THRESHOLD_DP.dp.toPx() }

    pointerInput(currentTabIndex) {
        detectHorizontalDragGestures(
            onHorizontalDrag = { change, dragAmount ->
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                totalDragDistance += dragAmount
            },
            onDragEnd = {
                val velocity = velocityTracker.calculateVelocity().x
                val dragDistance = totalDragDistance

                // Check if either velocity OR distance threshold is met
                val shouldNavigate = abs(velocity) >= DEFAULT_VELOCITY_THRESHOLD ||
                    abs(dragDistance) >= distanceThresholdPx

                if (shouldNavigate) {
                    swipeTargetIndex(
                        // Swipe right (previous tab) - positive velocity/drag
                        isPrevious = velocity > 0 || dragDistance > 0,
                        currentTabIndex = currentTabIndex,
                        tabCount = tabCount,
                        wrapAround = wrapAround,
                    )?.let(onTabChange)
                }

                velocityTracker.resetTracking()
                totalDragDistance = 0f
            },
            onDragCancel = {
                velocityTracker.resetTracking()
                totalDragDistance = 0f
            },
        )
    }
}

private fun swipeTargetIndex(isPrevious: Boolean, currentTabIndex: Int, tabCount: Int, wrapAround: Boolean): Int? =
    when {
        isPrevious && currentTabIndex > 0 -> currentTabIndex - 1
        isPrevious && wrapAround -> tabCount - 1
        !isPrevious && currentTabIndex < tabCount - 1 -> currentTabIndex + 1
        !isPrevious && wrapAround -> 0
        else -> null
    }

/**
 * Reports a horizontal swipe that pushes past the first or last page of a scrollable pager, so the pager can wrap
 * around. It only observes the pointer: the pager keeps handling and consuming its own drag.
 *
 * @param state The pager's scroll state, read when the swipe starts
 * @param onSwipePastStart Callback invoked for a swipe toward the start on the first page (to the right in LTR)
 * @param onSwipePastEnd Callback invoked for a swipe toward the end on the last page (to the left in LTR)
 * @param velocityThreshold Velocity threshold in px/s (default: 600f)
 * @param distanceThreshold Distance threshold in dp (default: 50.dp)
 */
fun Modifier.swipePastEdges(
    state: ScrollableState,
    onSwipePastStart: () -> Unit,
    onSwipePastEnd: () -> Unit,
    velocityThreshold: Float = DEFAULT_VELOCITY_THRESHOLD,
    distanceThreshold: Float = DEFAULT_DISTANCE_THRESHOLD_DP,
): Modifier = composed {
    val currentOnSwipePastStart by rememberUpdatedState(onSwipePastStart)
    val currentOnSwipePastEnd by rememberUpdatedState(onSwipePastEnd)
    val distanceThresholdPx = with(LocalDensity.current) { distanceThreshold.dp.toPx() }
    val layoutDirection = LocalLayoutDirection.current

    pointerInput(state, layoutDirection) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val isAtStart = !state.canScrollBackward
            val isAtEnd = !state.canScrollForward
            if (!isAtStart && !isAtEnd) return@awaitEachGesture

            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)
            val up = awaitRelease(down, velocityTracker) ?: return@awaitEachGesture

            val drag = up.position - down.position
            val isSwipe = abs(drag.x) > viewConfiguration.touchSlop &&
                abs(drag.x) > abs(drag.y) &&
                (abs(velocityTracker.calculateVelocity().x) >= velocityThreshold || abs(drag.x) >= distanceThresholdPx)
            if (!isSwipe) return@awaitEachGesture

            // LazyRow scrolls the other way in RTL, so a swipe toward its start moves the finger left
            val towardStart = if (layoutDirection == LayoutDirection.Rtl) drag.x < 0 else drag.x > 0
            when {
                towardStart && isAtStart -> currentOnSwipePastStart()
                !towardStart && isAtEnd -> currentOnSwipePastEnd()
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitRelease(
    down: PointerInputChange,
    velocityTracker: VelocityTracker,
): PointerInputChange? {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: return null
        velocityTracker.addPosition(change.uptimeMillis, change.position)
        if (!change.pressed) return change
    }
}

// Reduced from 1500 to 600 for better iOS-like sensitivity
private const val DEFAULT_VELOCITY_THRESHOLD = 600f

// Added distance threshold: 50dp drag triggers navigation
private const val DEFAULT_DISTANCE_THRESHOLD_DP = 50f
