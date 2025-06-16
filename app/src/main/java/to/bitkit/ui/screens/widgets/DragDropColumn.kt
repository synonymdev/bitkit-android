package to.bitkit.ui.screens.widgets

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import to.bitkit.models.WidgetWithPosition

@Composable
fun DragDropColumn(
    items: List<WidgetWithPosition>,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (WidgetWithPosition, Boolean) -> Unit
) {
    var draggedItem by remember { mutableStateOf<Int?>(null) }
    var draggedItemOffset by remember { mutableStateOf(0f) }

    Column(
        modifier = modifier
    ) {
        items.forEachIndexed { index, item ->
            val isDragging = draggedItem == index

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = if (isDragging) draggedItemOffset else 0f
                    }
                    .zIndex(if (isDragging) 1f else 0f)
                    .then(
                        if (isDragging) {
                            Modifier.shadow(8.dp)
                        } else Modifier
                    )
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggedItem = index
                            },
                            onDragEnd = {
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
            ) {
                itemContent(item, isDragging)
            }

            // Add spacing between items (except after the last item)
            if (index < items.size - 1) {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
