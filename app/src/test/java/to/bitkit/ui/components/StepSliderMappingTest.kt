package to.bitkit.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class StepSliderMappingTest {

    @Test
    fun `pointer mapping mirrors physical x in rtl`() {
        assertEquals(20f, sliderPointerToLogical(x = 80f, width = 100f, isRtl = true))
        assertEquals(80f, sliderPointerToLogical(x = 80f, width = 100f, isRtl = false))
    }

    @Test
    fun `visual mapping mirrors logical x in rtl`() {
        assertEquals(20f, sliderLogicalToVisual(x = 80f, width = 100f, isRtl = true))
        assertEquals(80f, sliderLogicalToVisual(x = 80f, width = 100f, isRtl = false))
    }

    @Test
    fun `drag delta flips in rtl so thumb follows the finger`() {
        assertEquals(-12f, sliderDragDeltaToLogical(deltaX = 12f, isRtl = true))
        assertEquals(12f, sliderDragDeltaToLogical(deltaX = 12f, isRtl = false))
    }
}
