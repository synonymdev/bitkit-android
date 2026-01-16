package to.bitkit.ui.shared.toast

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import to.bitkit.models.Toast
import to.bitkit.models.ToastText
import to.bitkit.models.ToastType
import to.bitkit.test.BaseUnitTest
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ToastQueueTest : BaseUnitTest(StandardTestDispatcher()) {
    private lateinit var sut: ToastQueue

    @Before
    fun setUp() {
        sut = ToastQueue(testDispatcher)
    }

    @Test
    fun `enqueue shows toast immediately when queue empty`() = test {
        val toast = createToast()

        sut.enqueue(toast)

        assertEquals(toast, sut.currentToast.value)
    }

    @Test
    fun `enqueue queues toast when another is displayed`() = test {
        val toast1 = createToast(title = "First")
        val toast2 = createToast(title = "Second")

        sut.enqueue(toast1)
        sut.enqueue(toast2)

        assertEquals(ToastText.Literal("Second"), sut.currentToast.value?.title)
    }

    @Test
    fun `dismiss advances to next toast in queue`() = test {
        val toast1 = createToast(title = "First", autoHide = false)
        val toast2 = createToast(title = "Second", autoHide = false)

        sut.enqueue(toast1)
        sut.enqueue(toast2)

        assertEquals(ToastText.Literal("Second"), sut.currentToast.value?.title)

        sut.dismissCurrentToast()

        assertNull(sut.currentToast.value)
    }

    @Test
    fun `auto-hide timer dismisses toast after duration`() = test {
        val toast = createToast(autoHide = true)

        sut.enqueue(toast)

        assertEquals(toast, sut.currentToast.value)

        advanceTimeBy(3001)

        assertNull(sut.currentToast.value)
    }

    @Test
    fun `pause stops auto-hide timer`() = test {
        val toast = createToast(autoHide = true)

        sut.enqueue(toast)
        advanceTimeBy(1000)
        sut.pauseCurrentToast()
        advanceTimeBy(5000)

        assertEquals(toast, sut.currentToast.value)
    }

    @Test
    fun `resume restarts auto-hide timer`() = test {
        val toast = createToast(autoHide = true)

        sut.enqueue(toast)
        advanceTimeBy(1000)
        sut.pauseCurrentToast()
        advanceTimeBy(5000)
        sut.resumeCurrentToast()
        advanceTimeBy(2000)

        assertEquals(toast, sut.currentToast.value)

        advanceTimeBy(1001)

        assertNull(sut.currentToast.value)
    }

    @Test
    fun `max queue size drops oldest when exceeded`() = test {
        val toasts = (1..6).map { createToast(title = "Toast $it") }

        toasts.forEach { sut.enqueue(it) }

        assertEquals(ToastText.Literal("Toast 6"), sut.currentToast.value?.title)
    }

    @Test
    fun `clear removes all toasts and hides current`() = test {
        val toast1 = createToast(title = "First", autoHide = false)
        val toast2 = createToast(title = "Second", autoHide = false)

        sut.enqueue(toast1)
        sut.enqueue(toast2)
        sut.clear()

        assertNull(sut.currentToast.value)
    }

    @Test
    fun `non-auto-hide toast stays until dismissed`() = test {
        val toast = createToast(autoHide = false)

        sut.enqueue(toast)
        advanceTimeBy(10_000)

        assertEquals(toast, sut.currentToast.value)

        sut.dismissCurrentToast()

        assertNull(sut.currentToast.value)
    }

    private fun createToast(
        title: String = "Test Toast",
        body: String? = null,
        type: ToastType = ToastType.INFO,
        autoHide: Boolean = true,
    ) = Toast(
        type = type,
        title = ToastText.Literal(title),
        body = body?.let { ToastText.Literal(it) },
        autoHide = autoHide,
        duration = 3.seconds,
    )
}
