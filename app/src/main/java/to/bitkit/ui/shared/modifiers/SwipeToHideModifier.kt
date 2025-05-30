package to.bitkit.ui.shared.modifiers

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

fun Modifier.swipeToHide(
    enabled: Boolean = true,
    onSwipe: () -> Unit = {},
    swipeSensitivity: Float = 30f,
): Modifier = this.then(
    if (enabled) {
        Modifier.composed {
            var wasHandled by remember { mutableStateOf(false) }

            Modifier.pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        wasHandled = false
                    },
                    onDragEnd = {
                        wasHandled = false
                    },
                    onDrag = { _, dragAmount ->
                        if (wasHandled) return@detectDragGestures

                        val absVelocityX = abs(dragAmount.x)
                        val absVelocityY = abs(dragAmount.y)

                        // Only trigger if horizontal swipe is more prominent than vertical
                        if (absVelocityX > absVelocityY && absVelocityX > swipeSensitivity) {
                            onSwipe()
                            wasHandled = true
                        }
                    }
                )
            }
        }
    } else {
        Modifier
    }
)
