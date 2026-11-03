package to.bitkit.ui.shared.effects

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenshotBlockCounterTest {

    @Test
    fun `first enter enables blocking`() {
        val counter = ScreenshotBlockCounter(mutableMapOf())
        val window = Any()

        assertTrue(counter.enter(window))
    }

    @Test
    fun `second enter does not clear blocking`() {
        val counter = ScreenshotBlockCounter(mutableMapOf())
        val window = Any()

        counter.enter(window)

        assertFalse(counter.enter(window))
    }

    @Test
    fun `leave while another host remains keeps blocking`() {
        val counter = ScreenshotBlockCounter(mutableMapOf())
        val window = Any()

        counter.enter(window)
        counter.enter(window)

        assertFalse(counter.leave(window))
    }

    @Test
    fun `last leave clears blocking`() {
        val counter = ScreenshotBlockCounter(mutableMapOf())
        val window = Any()

        counter.enter(window)
        counter.enter(window)
        counter.leave(window)

        assertTrue(counter.leave(window))
    }
}
