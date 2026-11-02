package to.bitkit.services

import org.junit.After
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrezorUiHandlerTest {
    private companion object {
        const val ANSWER_WAIT_SECONDS = 1L
        const val POLLS = 200
        const val POLL_MS = 5L
    }

    private val sut = TrezorUiHandler()
    private val executor = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun `a refused request is answered at once and never shown`() {
        sut.refusePinRequests()

        assertEquals("", requestPin().get(ANSWER_WAIT_SECONDS, TimeUnit.SECONDS))
        assertFalse(sut.needsPinEntry.value)
    }

    @Test
    fun `requests are shown again once every refusal is lifted`() {
        sut.refusePinRequests()
        sut.refusePinRequests()
        sut.allowPinRequests()
        assertEquals("", requestPin().get(ANSWER_WAIT_SECONDS, TimeUnit.SECONDS))

        sut.allowPinRequests()
        val answer = requestPin()
        assertTrue(awaitRequest())

        sut.submitPin("1397")
        assertEquals("1397", answer.get(ANSWER_WAIT_SECONDS, TimeUnit.SECONDS))
    }

    private fun requestPin(): Future<String> = executor.submit<String> { sut.onPinRequest() }

    private fun awaitRequest(): Boolean {
        repeat(POLLS) {
            if (sut.needsPinEntry.value) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }
}
