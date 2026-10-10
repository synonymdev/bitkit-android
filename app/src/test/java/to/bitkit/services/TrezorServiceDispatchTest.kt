package to.bitkit.services

import org.junit.Test
import org.mockito.kotlin.mock
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.ServiceError
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class TrezorServiceDispatchTest : BaseUnitTest() {
    @Test
    fun `expired native queue skips the durable dispatch callback`() = test {
        val deadline = Instant.parse("2026-10-08T12:00:00Z")
        val service = TrezorService(mock(), mock(), object : Clock {
            override fun now() = Instant.parse("2026-10-08T12:00:01Z")
        })
        var markers = 0
        val result = runCatching {
            service.broadcastRawTxAtBoundary("not-a-transaction", "unused", deadline) { markers++ }
        }
        assertEquals(0, markers)
        assertTrue(
            generateSequence(result.exceptionOrNull()) { it.cause }.any { it is ServiceError.PaymentDeadlineExpired }
        )
    }

    @Test
    fun `expiry while the durable marker is saved skips native submission`() = test {
        val deadline = Instant.parse("2026-10-08T12:00:00Z")
        var now = Instant.parse("2026-10-08T11:59:59Z")
        val service = TrezorService(mock(), mock(), object : Clock {
            override fun now() = now
        })
        var markers = 0
        val result = runCatching {
            service.broadcastRawTxAtBoundary("not-a-transaction", "unused", deadline) {
                markers++
                now = Instant.parse("2026-10-08T12:00:01Z")
            }
        }
        assertEquals(1, markers)
        assertTrue(
            generateSequence(result.exceptionOrNull()) { it.cause }.any { it is ServiceError.PaymentDeadlineExpired }
        )
    }
}
