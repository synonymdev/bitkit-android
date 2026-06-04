package to.bitkit.ui.screens.widgets.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList

/** A placed widget in the home grid: the child's index and its frame within the grid bounds (px). */
data class WidgetSlot(
    val index: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class WidgetGridResult(
    val slots: List<WidgetSlot>,
    val totalHeight: Int,
)

/**
 * Pure two-column flow geometry (px). Wide widgets span the full width on their own row; consecutive
 * small widgets pair side by side (a lone trailing small occupies the left column). Paired smalls
 * share the taller of the two heights. Mirrors iOS `widgetGridSlots`.
 */
fun widgetGridSlots(
    isWide: List<Boolean>,
    totalWidth: Int,
    spacingPx: Int,
    heights: List<Int>,
    smallHeightPx: Int,
): WidgetGridResult {
    val columnWidth = (totalWidth - spacingPx) / 2
    val slots = ArrayList<WidgetSlot>(isWide.size)
    var y = 0
    var i = 0

    while (i < isWide.size) {
        when {
            isWide[i] -> {
                val height = heightAt(heights, i, smallHeightPx)
                slots.add(fullWidthSlot(index = i, y = y, totalWidth = totalWidth, height = height))
                y += height + spacingPx
                i += 1
            }

            canPairWithNext(isWide, i) -> {
                val rowHeight = maxOf(
                    heightAt(heights, i, smallHeightPx),
                    heightAt(heights, i + 1, smallHeightPx),
                )
                slots.addAll(
                    pairedRowSlots(
                        index = i,
                        y = y,
                        columnWidth = columnWidth,
                        spacingPx = spacingPx,
                        rowHeight = rowHeight,
                    )
                )
                y += rowHeight + spacingPx
                i += 2
            }

            else -> {
                val height = heightAt(heights, i, smallHeightPx)
                slots.add(leftColumnSlot(index = i, y = y, columnWidth = columnWidth, height = height))
                y += height + spacingPx
                i += 1
            }
        }
    }

    return WidgetGridResult(slots = slots, totalHeight = maxOf(0, y - spacingPx))
}

private fun heightAt(heights: List<Int>, index: Int, fallback: Int): Int =
    heights.getOrElse(index) { fallback }

private fun canPairWithNext(isWide: List<Boolean>, index: Int): Boolean {
    val next = index + 1
    val nextExists = next < isWide.size
    return nextExists && !isWide[next]
}

private fun fullWidthSlot(index: Int, y: Int, totalWidth: Int, height: Int): WidgetSlot =
    WidgetSlot(index = index, x = 0, y = y, width = totalWidth, height = height)

private fun leftColumnSlot(index: Int, y: Int, columnWidth: Int, height: Int): WidgetSlot =
    WidgetSlot(index = index, x = 0, y = y, width = columnWidth, height = height)

private fun pairedRowSlots(index: Int, y: Int, columnWidth: Int, spacingPx: Int, rowHeight: Int): List<WidgetSlot> =
    listOf(
        WidgetSlot(index = index, x = 0, y = y, width = columnWidth, height = rowHeight),
        WidgetSlot(
            index = index + 1,
            x = columnWidth + spacingPx,
            y = y,
            width = columnWidth,
            height = rowHeight,
        ),
    )

/**
 * Resolves which widget cell a drag point targets, given each cell's frame (grid coords). Picks the
 * nearest by vertical band distance, then horizontal band distance, then biases toward the lower
 * slot so a drop in an inter-row gap targets the row below. Mirrors iOS `nearestWidgetSlot`.
 */
fun <K> nearestWidgetSlot(finger: Offset, bounds: Map<K, Rect>): K? {
    if (bounds.isEmpty()) return null
    return bounds.entries
        .minWithOrNull(
            compareBy(
                { axisDistance(finger.y, it.value.top, it.value.bottom) },
                { axisDistance(finger.x, it.value.left, it.value.right) },
                { -it.value.top },
            )
        )
        ?.key
}

private fun axisDistance(value: Float, min: Float, max: Float): Float = when {
    value < min -> min - value
    value > max -> value - max
    else -> 0f
}

/**
 * Two-column flow layout for the home widget grid. [isWide] is parallel to [content]'s children
 * order. Small children are measured at a fixed column width and [smallHeight]; wide children span
 * the full width with free height.
 */
@Composable
fun WidgetFlowLayout(
    isWide: ImmutableList<Boolean>,
    modifier: Modifier = Modifier,
    spacing: Dp = 16.dp,
    smallHeight: Dp = WidgetCardDimens.COMPACT_CARD_SIZE.height,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val spacingPx = spacing.roundToPx()
        val smallHeightPx = smallHeight.roundToPx()
        val totalWidth = constraints.maxWidth
        val columnWidth = (totalWidth - spacingPx) / 2
        val wideFlags = measurables.indices.map { isWide.getOrElse(it) { true } }

        val placeables = measurables.mapIndexed { index, measurable ->
            if (wideFlags[index]) {
                measurable.measure(
                    constraints.copy(minWidth = totalWidth, maxWidth = totalWidth, minHeight = 0)
                )
            } else {
                measurable.measure(Constraints.fixed(columnWidth, smallHeightPx))
            }
        }

        val heights = placeables.mapIndexed { index, placeable ->
            if (wideFlags[index]) placeable.height else smallHeightPx
        }

        val result = widgetGridSlots(
            isWide = wideFlags,
            totalWidth = totalWidth,
            spacingPx = spacingPx,
            heights = heights,
            smallHeightPx = smallHeightPx,
        )

        layout(totalWidth, result.totalHeight) {
            result.slots.forEach { slot ->
                placeables[slot.index].placeRelative(slot.x, slot.y)
            }
        }
    }
}
