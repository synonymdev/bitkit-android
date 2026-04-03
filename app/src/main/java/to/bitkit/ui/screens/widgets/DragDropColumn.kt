package to.bitkit.ui.screens.widgets

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.collections.immutable.ImmutableList
import to.bitkit.models.WidgetWithPosition
import to.bitkit.ui.components.VerticalSpacer

private const val DRAG_SCALE = 1.05f

@Composable
fun DragDropColumn(
    items: ImmutableList<WidgetWithPosition>,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (WidgetWithPosition, Boolean, Modifier) -> Unit,
) {
    var draggedItem by remember { mutableStateOf<Int?>(null) }
    var draggedItemOffset by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
    ) {
        items.forEachIndexed { index, item ->
            val isDragging = draggedItem == index

            val dragModifier = Modifier.pointerInput(index) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        draggedItem = index
                    },
                    onDragEnd = {
                        draggedItem = null
                        draggedItemOffset = 0f
                    },
                    onDragCancel = {
                        draggedItem = null
                        draggedItemOffset = 0f
                    },
                    onDrag = { _, dragAmount ->
                        draggedItemOffset += dragAmount.y

                        val itemHeight = 96.dp.toPx() // Item height + spacing (80dp + 16dp)
                        val draggedIndex = draggedItem ?: index

                        // Calculate how many positions we've moved
                        val positionChange = (draggedItemOffset / itemHeight).toInt()
                        val newPosition = (draggedIndex + positionChange).coerceIn(0, items.size - 1)

                        if (newPosition != draggedIndex) {
                            onMove(draggedIndex, newPosition)
                            draggedItem = newPosition
                            // Reset offset after moving to prevent accumulation
                            draggedItemOffset = draggedItemOffset % itemHeight
                        }
                    }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = if (isDragging) draggedItemOffset else 0f
                        scaleX = if (isDragging) DRAG_SCALE else 1f
                        scaleY = if (isDragging) DRAG_SCALE else 1f
                    }
                    .zIndex(if (isDragging) 1f else 0f)
            ) {
                itemContent(item, isDragging, dragModifier)
            }

            // Add spacing between items (except after the last item)
            if (index < items.size - 1) {
                VerticalSpacer(16.dp)
            }
        }
    }
}
