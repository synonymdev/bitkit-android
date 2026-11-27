// Copied from app/src/main/java/to/bitkit/ui/shared/modifiers/ClickableAlpha.kt (tap only); keep both in sync.
package to.bitkit.wear.ui.modifiers

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
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
import kotlin.time.Duration.Companion.milliseconds

/** The shortest time between two taps that both count, as on the phone. */
private val CLICK_DEBOUNCE = 500.milliseconds

/** The alpha of pressed content, as on the phone. */
private const val PRESSED_ALPHA = 0.7f

/**
 * Makes the content clickable with the phone's feedback: it fades while pressed, with no ripple, and repeated taps
 * within [CLICK_DEBOUNCE] count once. [onClickLabel] names the action for accessibility services.
 */
fun Modifier.clickableAlpha(
    onClickLabel: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = if (enabled) this.then(ClickableAlphaElement(onClickLabel, onClick)) else this

private class ClickDebouncer {
    private var lastClickTime = 0L

    fun tryClick(onClick: () -> Unit): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastClickTime < CLICK_DEBOUNCE.inWholeMilliseconds) return false
        lastClickTime = now
        onClick()
        return true
    }
}

private data class ClickableAlphaElement(
    val onClickLabel: String?,
    val onClick: () -> Unit,
) : ModifierNodeElement<ClickableAlphaNode>() {
    override fun create(): ClickableAlphaNode = ClickableAlphaNode(onClickLabel, onClick)

    override fun update(node: ClickableAlphaNode) {
        node.onClickLabel = onClickLabel
        node.onClick = onClick
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "clickableAlpha"
        properties["onClickLabel"] = onClickLabel
        properties["onClick"] = onClick
    }
}

private class ClickableAlphaNode(
    var onClickLabel: String?,
    var onClick: () -> Unit,
) : DelegatingNode(), LayoutModifierNode, SemanticsModifierNode {
    private val animatable = Animatable(1f)
    private val debouncer = ClickDebouncer()

    init {
        delegate(
            SuspendingPointerInputModifierNode {
                detectTapGestures(
                    onPress = {
                        coroutineScope.launch { animatable.animateTo(PRESSED_ALPHA) }
                        if (!tryAwaitRelease()) coroutineScope.launch { animatable.animateTo(1f) }
                    },
                    onTap = {
                        if (debouncer.tryClick(onClick)) {
                            coroutineScope.launch {
                                animatable.animateTo(PRESSED_ALPHA)
                                animatable.animateTo(1f)
                            }
                        } else {
                            coroutineScope.launch { animatable.animateTo(1f) }
                        }
                    },
                )
            }
        )
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) { alpha = animatable.value }
        }
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        role = Role.Button
        onClick(label = onClickLabel) {
            debouncer.tryClick(onClick)
            true
        }
    }
}
