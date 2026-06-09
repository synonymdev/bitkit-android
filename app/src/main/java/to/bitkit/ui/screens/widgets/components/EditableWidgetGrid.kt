package to.bitkit.ui.screens.widgets.components

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import to.bitkit.R
import to.bitkit.models.WidgetSize
import to.bitkit.models.WidgetType
import to.bitkit.models.WidgetWithPosition
import to.bitkit.models.effectiveSize
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.theme.Colors
import kotlin.math.roundToInt

private const val DRAGGED_ALPHA = 0.3f
private const val DRAG_PREVIEW_ALPHA = 0.8f
private const val REORDER_DAMPING = 0.85f

@OptIn(ExperimentalSharedTransitionApi::class)
private val ReorderBoundsTransform = BoundsTransform { _, _ ->
    spring(dampingRatio = REORDER_DAMPING, stiffness = Spring.StiffnessMediumLow)
}

/**
 * Edit-mode home grid: renders the real widget cards in the two-column flow layout wrapped in the
 * dashed editing overlay, with location-based drag reordering (a floating preview follows the
 * finger) and spring placement animations mirroring iOS.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun EditableWidgetGrid(
    items: ImmutableList<WidgetWithPosition>,
    onMove: (from: Int, to: Int) -> Unit,
    onDelete: (WidgetType) -> Unit,
    onSettings: (WidgetType) -> Unit,
    modifier: Modifier = Modifier,
    cardContent: @Composable (WidgetWithPosition) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val cellBounds = remember { mutableStateMapOf<WidgetType, Rect>() }
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }
    var draggedType by remember { mutableStateOf<WidgetType?>(null) }
    var dragPointer by remember { mutableStateOf(Offset.Zero) }
    // The last slot we reordered onto, so we only act when the target changes (mirrors iOS).
    var lastTarget by remember { mutableStateOf<WidgetType?>(null) }
    val latestItems by rememberUpdatedState(items)

    val isWide = items.map { it.effectiveSize() == WidgetSize.WIDE }.toImmutableList()

    Box(modifier = modifier.onGloballyPositioned { gridOrigin = it.positionInRoot() }) {
        LookaheadScope {
            WidgetFlowLayout(isWide = isWide, modifier = Modifier.fillMaxWidth()) {
                items.forEach { widget ->
                    key(widget.type) {
                        val isDragging = draggedType == widget.type
                        val dragHandleModifier = Modifier.pointerInput(widget.type) {
                            detectReorderDrag(
                                type = widget.type,
                                cellBounds = cellBounds,
                                onStart = { center ->
                                    draggedType = widget.type
                                    dragPointer = center
                                    lastTarget = null
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { delta ->
                                    dragPointer += delta
                                    val source = draggedType
                                    val target = nearestWidgetSlot(dragPointer, cellBounds)
                                    when {
                                        source == null || target == null -> Unit
                                        // back over dragged cell: re-allow visited slots
                                        target == source -> lastTarget = null
                                        // act only when target changes (avoid oscillation)
                                        target != lastTarget -> {
                                            val from = latestItems.indexOfFirst { it.type == source }
                                            val to = latestItems.indexOfFirst { it.type == target }
                                            if (from >= 0 && to >= 0) {
                                                onMove(from, to)
                                                lastTarget = target
                                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                            }
                                        }
                                    }
                                },
                                onEnd = {
                                    draggedType = null
                                    lastTarget = null
                                },
                            )
                        }

                        Box(
                            modifier = Modifier
                                .animateBounds(this@LookaheadScope, boundsTransform = ReorderBoundsTransform)
                                .onGloballyPositioned { cellBounds[widget.type] = it.boundsInRoot() }
                                .alpha(if (isDragging) DRAGGED_ALPHA else 1f)
                        ) {
                            EditWidgetOverlay(
                                type = widget.type,
                                onDelete = { onDelete(widget.type) },
                                onSettings = { onSettings(widget.type) },
                                dragHandleModifier = dragHandleModifier,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                cardContent(widget)
                            }
                        }
                    }
                }
            }
        }

        draggedType?.let { type ->
            val bounds = cellBounds[type] ?: return@let
            Box(
                modifier = Modifier
                    .zIndex(1f)
                    .offset {
                        IntOffset(
                            x = (dragPointer.x - gridOrigin.x - bounds.width / 2f).roundToInt(),
                            y = (dragPointer.y - gridOrigin.y - bounds.height / 2f).roundToInt(),
                        )
                    }
                    .size(
                        width = with(density) { bounds.width.toDp() },
                        height = with(density) { bounds.height.toDp() },
                    )
                    .alpha(DRAG_PREVIEW_ALPHA)
            ) {
                DragPreviewCard(type = type, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

private suspend fun PointerInputScope.detectReorderDrag(
    type: WidgetType,
    cellBounds: Map<WidgetType, Rect>,
    onStart: (center: Offset) -> Unit,
    onDrag: (delta: Offset) -> Unit,
    onEnd: () -> Unit,
) {
    detectDragGestures(
        onDragStart = { onStart(cellBounds[type]?.center ?: Offset.Zero) },
        onDrag = { change, dragAmount ->
            change.consume()
            onDrag(dragAmount)
        },
        onDragEnd = onEnd,
        onDragCancel = onEnd,
    )
}

@Composable
private fun DragPreviewCard(
    type: WidgetType,
    modifier: Modifier = Modifier,
) {
    val name = stringResource(type.title)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Colors.Gray6)
            .drawBehind {
                drawRoundRect(
                    color = Colors.Brand,
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                    ),
                )
            }
            .padding(8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BodyMSB(text = name, textAlign = TextAlign.Center)
            VerticalSpacer(12.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    R.drawable.ic_trash,
                    R.drawable.ic_settings,
                    R.drawable.ic_arrows_out_cardinal,
                ).forEach { icon ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            painter = painterResource(icon),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}
