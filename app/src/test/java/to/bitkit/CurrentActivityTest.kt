package to.bitkit

import android.app.Activity
import org.junit.Test
import org.mockito.kotlin.mock
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurrentActivityTest {
    private val sut = CurrentActivity()
    private val activity = mock<Activity>()

    @Test
    fun `has no started activity before any start`() {
        assertFalse(sut.hasStartedActivity)
    }

    @Test
    fun `keeps a paused activity started while it stays visible`() {
        sut.onActivityStarted(activity)
        sut.onActivityResumed(activity)

        sut.onActivityPaused(activity)

        assertTrue(sut.hasStartedActivity)
        assertNull(sut.value)
    }

    @Test
    fun `has no started activity once every started activity stops`() {
        val other = mock<Activity>()
        sut.onActivityStarted(activity)
        sut.onActivityStarted(other)

        sut.onActivityStopped(activity)
        assertTrue(sut.hasStartedActivity)

        sut.onActivityStopped(other)
        assertFalse(sut.hasStartedActivity)
    }

    @Test
    fun `ignores a stop without a matching start`() {
        sut.onActivityStopped(activity)
        sut.onActivityStarted(activity)

        assertTrue(sut.hasStartedActivity)
    }
}
