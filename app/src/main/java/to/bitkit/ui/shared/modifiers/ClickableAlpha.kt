package to.bitkit.ui.shared.modifiers

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val CLICK_DEBOUNCE = 500.milliseconds

private class ClickDebouncer {
    private var lastClickTime = 0L

    fun tryClick(debounce: Duration = CLICK_DEBOUNCE, onClick: () -> Unit): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastClickTime >= debounce.inWholeMilliseconds) {
            lastClickTime = now
            onClick()
            return true
        }
        return false
    }
}

@Composable
fun rememberDebouncedClick(debounce: Duration = CLICK_DEBOUNCE, onClick: () -> Unit): () -> Unit {
    val debouncer = remember { ClickDebouncer() }
    val currentOnClick by rememberUpdatedState(onClick)
    return remember(debouncer, debounce) { { debouncer.tryClick(debounce, currentOnClick) } }
}

/**
 * Adjusts the alpha of a composable when it is pressed and makes it clickable.
 * When pressed, the alpha is reduced to provide visual feedback.
 * If `onClick` is null or `enabled` is false, the clickable behavior is disabled.
 *
 * Set `ripple` to true to show the standard Material ripple indication alongside
 * the alpha animation (useful for list items, menu buttons, etc.).
 *
 * Analogue of `TouchableOpacity` in React Native.
 */
@Composable
fun Modifier.clickableAlpha(
    pressedAlpha: Float = 0.7f,
    enabled: Boolean = true,
    ripple: Boolean = false,
    debounce: Duration = CLICK_DEBOUNCE,
    onClick: (() -> Unit)?,
): Modifier = when {
    onClick == null || !enabled -> this
    ripple ->
        this
            .alphaFeedback(pressedAlpha)
            .clickable(onClick = rememberDebouncedClick(debounce, onClick))

    else -> this.then(ClickableAlphaElement(pressedAlpha, debounce, onClick))
}

private data class ClickableAlphaElement(
    val pressedAlpha: Float,
    val debounce: Duration,
    val onClick: () -> Unit,
) : ModifierNodeElement<ClickableAlphaNode>() {
    override fun create(): ClickableAlphaNode = ClickableAlphaNode(pressedAlpha, debounce, onClick)

    override fun update(node: ClickableAlphaNode) {
        node.pressedAlpha = pressedAlpha
        node.debounce = debounce
        node.onClick = onClick
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "clickableAlpha"
        properties["pressedAlpha"] = pressedAlpha
        properties["debounce"] = debounce
        properties["onClick"] = onClick
    }
}

private class ClickableAlphaNode(
    var pressedAlpha: Float,
    var debounce: Duration,
    var onClick: () -> Unit,
) : DelegatingNode(), LayoutModifierNode, SemanticsModifierNode {

    private val animatable = Animatable(1f)
    private val debouncer = ClickDebouncer()

    init {
        delegate(
            SuspendingPointerInputModifierNode {
                detectTapGestures(
                    onPress = {
                        coroutineScope.launch { animatable.animateTo(pressedAlpha) }
                        val released = tryAwaitRelease()
                        if (!released) {
                            coroutineScope.launch { animatable.animateTo(1f) }
                        }
                    },
                    onTap = {
                        if (debouncer.tryClick(debounce, onClick)) {
                            coroutineScope.launch {
                                animatable.animateTo(pressedAlpha)
                                animatable.animateTo(1f)
                            }
                        } else {
                            coroutineScope.launch { animatable.animateTo(1f) }
                        }
                    }
                )
            }
        )
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)

        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                this.alpha = animatable.value
            }
        }
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        role = Role.Button
        onClick {
            debouncer.tryClick(debounce, onClick)
            true
        }
    }
}

/**
 * Applies alpha animation feedback on press without consuming click events.
 * This allows the Button's onClick to work while providing full-area visual feedback.
 */
@Composable
fun Modifier.alphaFeedback(
    pressedAlpha: Float = 0.7f,
    enabled: Boolean = true,
): Modifier = if (enabled) {
    val animatable = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    this
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    scope.launch { animatable.animateTo(pressedAlpha) }
                    tryAwaitRelease()
                    scope.launch { animatable.animateTo(1f) }
                },
                onTap = null // Don't consume tap - let Button handle it
            )
        }
        .graphicsLayer {
            alpha = animatable.value
        }
} else {
    this
}
