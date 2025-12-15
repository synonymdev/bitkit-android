package to.bitkit.ui.shared.modifiers

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

/**
 * Adjusts the alpha of a composable when it is pressed and makes it clickable.
 * When pressed, the alpha is reduced to provide visual feedback.
 * If `onClick` is null, the clickable behavior is disabled.
 *
 * Analogue of `TouchableOpacity` in React Native.
 */
fun Modifier.clickableAlpha(
    pressedAlpha: Float = 0.7f,
    onClick: (() -> Unit)?,
): Modifier = if (onClick != null) {
    this.then(ClickableAlphaElement(pressedAlpha, onClick))
} else {
    this
}

private data class ClickableAlphaElement(
    val pressedAlpha: Float,
    val onClick: () -> Unit,
) : ModifierNodeElement<ClickableAlphaNode>() {
    override fun create(): ClickableAlphaNode = ClickableAlphaNode(pressedAlpha, onClick)

    override fun update(node: ClickableAlphaNode) {
        node.pressedAlpha = pressedAlpha
        node.onClick = onClick
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "clickableAlpha"
        properties["pressedAlpha"] = pressedAlpha
        properties["onClick"] = onClick
    }
}

private class ClickableAlphaNode(
    var pressedAlpha: Float,
    var onClick: () -> Unit,
) : DelegatingNode(), LayoutModifierNode, SemanticsModifierNode {

    private val animatable = Animatable(1f)

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
                        onClick()
                        coroutineScope.launch {
                            animatable.animateTo(pressedAlpha)
                            animatable.animateTo(1f)
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
            onClick()
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
