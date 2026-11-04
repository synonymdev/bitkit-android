package to.bitkit.repositories

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HwHeldDispatchTrackerTest {
    private val sut = HwHeldDispatchTracker()

    @Test
    fun `one dispatch of the same bytes runs at a time, and an expired one counts as possibly sent`() {
        val first = assertNotNull(sut.begin("AB"))
        assertNull(sut.begin("ab"))
        assertEquals(setOf("ab"), sut.inFlight.value)

        sut.expire(first)
        sut.finish(first, isNotSent = true)
        val second = assertNotNull(sut.begin("ab"))

        assertTrue(sut.inFlight.value.contains("ab"))
        assertFalse(sut.isOnlyPossibleSend(second))
    }

    @Test
    fun `a dispatch proven not sent is pruned, and the rest are forgotten once the hold is released`() {
        val notSent = assertNotNull(sut.begin("ab"))
        sut.finish(notSent, isNotSent = true)
        val maybeSent = assertNotNull(sut.begin("ab"))
        sut.finish(maybeSent, isNotSent = false)
        val last = assertNotNull(sut.begin("ab"))

        assertFalse(sut.isOnlyPossibleSend(last))

        sut.forget("AB")

        assertTrue(sut.isOnlyPossibleSend(last))
        assertEquals(setOf("ab"), sut.inFlight.value)
    }
}
