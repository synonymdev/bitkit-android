package to.bitkit.ui.screens.widgets.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WidgetGridSlotsTest {

    private val width = 332
    private val spacing = 16
    private val smallHeight = 192
    private val columnWidth = (width - spacing) / 2 // 158

    private fun heights(vararg values: Int) = values.toList()

    @Test
    fun `empty input produces no slots and zero height`() {
        val result = widgetGridSlots(emptyList(), width, spacing, emptyList(), smallHeight)
        assertEquals(emptyList(), result.slots)
        assertEquals(0, result.totalHeight)
    }

    @Test
    fun `wide widgets stack full width`() {
        val result = widgetGridSlots(
            isWide = listOf(true, true),
            totalWidth = width,
            spacingPx = spacing,
            heights = heights(100, 120),
            smallHeightPx = smallHeight,
        )
        assertEquals(WidgetSlot(0, 0, 0, width, 100), result.slots[0])
        assertEquals(WidgetSlot(1, 0, 116, width, 120), result.slots[1])
        assertEquals(236, result.totalHeight) // 100 + 16 + 120
    }

    @Test
    fun `consecutive smalls pair side by side`() {
        val result = widgetGridSlots(
            isWide = listOf(false, false),
            totalWidth = width,
            spacingPx = spacing,
            heights = heights(smallHeight, smallHeight),
            smallHeightPx = smallHeight,
        )
        assertEquals(WidgetSlot(0, 0, 0, columnWidth, smallHeight), result.slots[0])
        assertEquals(WidgetSlot(1, columnWidth + spacing, 0, columnWidth, smallHeight), result.slots[1])
        assertEquals(smallHeight, result.totalHeight)
    }

    @Test
    fun `lone trailing small occupies left column`() {
        val result = widgetGridSlots(
            isWide = listOf(false),
            totalWidth = width,
            spacingPx = spacing,
            heights = heights(smallHeight),
            smallHeightPx = smallHeight,
        )
        assertEquals(WidgetSlot(0, 0, 0, columnWidth, smallHeight), result.slots.single())
        assertEquals(smallHeight, result.totalHeight)
    }

    @Test
    fun `mixed wide then paired smalls`() {
        val result = widgetGridSlots(
            isWide = listOf(true, false, false),
            totalWidth = width,
            spacingPx = spacing,
            heights = heights(100, smallHeight, smallHeight),
            smallHeightPx = smallHeight,
        )
        assertEquals(WidgetSlot(0, 0, 0, width, 100), result.slots[0])
        assertEquals(WidgetSlot(1, 0, 116, columnWidth, smallHeight), result.slots[1])
        assertEquals(WidgetSlot(2, columnWidth + spacing, 116, columnWidth, smallHeight), result.slots[2])
        assertEquals(308, result.totalHeight) // 100 + 16 + 192
    }

    @Test
    fun `nearest slot returns null for empty bounds`() {
        assertNull(nearestWidgetSlot(Offset(10f, 10f), emptyMap<Int, Rect>()))
    }

    @Test
    fun `nearest slot picks the containing cell`() {
        val bounds = mapOf(
            0 to Rect(0f, 0f, 100f, 100f),
            1 to Rect(0f, 116f, 100f, 216f),
        )
        assertEquals(0, nearestWidgetSlot(Offset(50f, 50f), bounds))
        assertEquals(1, nearestWidgetSlot(Offset(50f, 150f), bounds))
    }

    @Test
    fun `nearest slot biases gap drops to the lower row`() {
        val bounds = mapOf(
            0 to Rect(0f, 0f, 100f, 100f),
            1 to Rect(0f, 120f, 100f, 220f),
        )
        // A point centred in the gap is equidistant; the lower row wins.
        assertEquals(1, nearestWidgetSlot(Offset(50f, 110f), bounds))
    }
}
