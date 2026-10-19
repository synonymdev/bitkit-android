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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
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
 * @param velocityThreshold Velocity threshold in px/s (default: 600f)
 * @param distanceThreshold Distance threshold in dp (default: 50.dp)
 */
fun Modifier.swipeToChangeTab(
    currentTabIndex: Int,
    tabCount: Int,
    onTabChange: (Int) -> Unit,
    wrapAround: Boolean = false,
    velocityThreshold: Float = DEFAULT_VELOCITY_THRESHOLD,
    distanceThreshold: Float = DEFAULT_DISTANCE_THRESHOLD_DP,
): Modifier = composed {
    val velocityTracker = remember { VelocityTracker() }
    var totalDragDistance by remember { mutableFloatStateOf(0f) }
    val distanceThresholdPx = with(LocalDensity.current) { distanceThreshold.dp.toPx() }

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
                val shouldNavigate = abs(velocity) >= velocityThreshold ||
                    abs(dragDistance) >= distanceThresholdPx

                if (shouldNavigate) {
                    when {
                        // Swipe right (previous tab) - positive velocity/drag
                        (velocity > 0 || dragDistance > 0) && currentTabIndex > 0 ->
                            onTabChange(currentTabIndex - 1)

                        (velocity > 0 || dragDistance > 0) && wrapAround -> onTabChange(tabCount - 1)

                        // Swipe left (next tab) - negative velocity/drag
                        (velocity < 0 || dragDistance < 0) && currentTabIndex < tabCount - 1 ->
                            onTabChange(currentTabIndex + 1)

                        (velocity < 0 || dragDistance < 0) && wrapAround -> onTabChange(0)
                    }
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

/**
 * Reports a horizontal swipe that pushes past the first or last page of a scrollable pager, so the pager can wrap
 * around. It only observes the pointer: the pager keeps handling and consuming its own drag.
 *
 * @param state The pager's scroll state, read when the swipe starts
 * @param onSwipePastStart Callback invoked for a swipe to the right on the first page
 * @param onSwipePastEnd Callback invoked for a swipe to the left on the last page
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

    pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val isAtStart = !state.canScrollBackward
            val isAtEnd = !state.canScrollForward
            if (!isAtStart && !isAtEnd) return@awaitEachGesture

            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)
            var position = down.position
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Final).changes
                    .firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                position = change.position
                if (!change.pressed) break
            }

            val dragX = position.x - down.position.x
            val dragY = position.y - down.position.y
            val velocity = velocityTracker.calculateVelocity().x
            val isSwipe = abs(dragX) > abs(dragY) &&
                (abs(velocity) >= velocityThreshold || abs(dragX) >= distanceThresholdPx)
            if (!isSwipe) return@awaitEachGesture

            when {
                dragX > 0 && isAtStart -> currentOnSwipePastStart()
                dragX < 0 && isAtEnd -> currentOnSwipePastEnd()
            }
        }
    }
}

// Reduced from 1500 to 600 for better iOS-like sensitivity
private const val DEFAULT_VELOCITY_THRESHOLD = 600f

// Added distance threshold: 50dp drag triggers navigation
private const val DEFAULT_DISTANCE_THRESHOLD_DP = 50f
