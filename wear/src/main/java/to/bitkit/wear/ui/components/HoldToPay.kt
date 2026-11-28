package to.bitkit.wear.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.wear.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.flow.collectLatest
import kotlin.time.Duration.Companion.milliseconds

/** How long the wearer holds the pay button before the payment goes out. */
val HOLD_TO_PAY_DURATION = 1_200.milliseconds

/** How many light ticks the hold gives on its way; the last one is the confirming haptic. */
private const val HOLD_STEPS = 4

/** How long the ring takes to empty again after an early release. */
private val RELEASE_DURATION = 200.milliseconds

/** The progress of a hold to pay, shared by the button that is held and the ring that fills around the bezel. */
@Stable
class HoldToPayState internal constructor() {
    /** The pressed state of the held button; pass it to the button as its interaction source. */
    val interactionSource = MutableInteractionSource()
    internal val progress = Animatable(0f)
}

/**
 * Follows the presses of [HoldToPayState.interactionSource]: while [enabled] and held, the ring fills over
 * [HOLD_TO_PAY_DURATION] with a light tick at each step and a confirming haptic at the end, and only then
 * [onComplete] runs. Releasing early empties the ring and pays nothing; a tap never pays.
 */
@Composable
fun rememberHoldToPayState(
    enabled: Boolean,
    onComplete: () -> Unit,
): HoldToPayState {
    val state = remember { HoldToPayState() }
    val haptic = LocalHapticFeedback.current
    val currentOnComplete by rememberUpdatedState(onComplete)
    LaunchedEffect(state, enabled) {
        if (!enabled) {
            state.progress.snapTo(0f)
            return@LaunchedEffect
        }
        state.interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    state.progress.snapTo(0f)
                    val step = HOLD_TO_PAY_DURATION / HOLD_STEPS
                    for (index in 1..HOLD_STEPS) {
                        state.progress.animateTo(
                            targetValue = index.toFloat() / HOLD_STEPS,
                            animationSpec = tween(step.inWholeMilliseconds.toInt(), easing = LinearEasing),
                        )
                        if (index < HOLD_STEPS) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    currentOnComplete()
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> state.progress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(RELEASE_DURATION.inWholeMilliseconds.toInt()),
                )
            }
        }
    }
    return state
}

/** The ring of a hold to pay around the screen edge, in the geometry of [BezelLoading]. Empty until held. */
@Composable
fun HoldToPayRing(
    state: HoldToPayState,
    modifier: Modifier = Modifier,
) {
    if (isAmbient()) return
    CircularProgressIndicator(
        progress = { state.progress.value },
        strokeWidth = BezelStrokeWidth,
        modifier = modifier
            .fillMaxSize()
            .padding(BezelPadding)
            .clearAndSetSemantics {}
    )
}
