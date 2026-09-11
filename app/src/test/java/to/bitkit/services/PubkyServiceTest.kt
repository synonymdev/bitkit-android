package to.bitkit.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import to.bitkit.async.ServiceQueue
import to.bitkit.ext.runSuspendCatching
import to.bitkit.test.BaseUnitTest
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PubkyServiceTest : BaseUnitTest() {
    @Test
    fun `relay timeout cancels the queued call and allows another approval`() = test {
        ServiceQueue.CORE.background {
            val binding = Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt")
            val approve = binding.getMethod(
                "approvePubkyAuth",
                String::class.java,
                String::class.java,
                Continuation::class.java,
            )
            val sut = PubkyService(mock())
            var cancelled = false
            mockStatic(binding).use { native ->
                native.`when`<Any?> { approve.invoke(null, "auth", "secret", null) }.thenAnswer {
                    @Suppress("UNCHECKED_CAST")
                    val continuation = it.rawArguments.last() as Continuation<Unit>
                    suspend {
                        try {
                            delay(1.seconds)
                        } finally {
                            cancelled = true
                        }
                    }.startCoroutineUninterceptedOrReturn(continuation)
                }.thenReturn(Unit)

                val result = runSuspendCatching { sut.approveRingAuth("auth", "secret", 50.milliseconds) }

                assertIs<PubkyRingAuthTimeoutError>(result.exceptionOrNull()?.cause)
                assertTrue(cancelled)
                sut.approveRingAuth("auth", "secret", 50.milliseconds)
            }
        }
    }

    @Test
    fun `relay cancellation propagates without becoming a timeout failure`() = test {
        ServiceQueue.CORE.background {
            val binding = Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt")
            val approve = binding.getMethod(
                "approvePubkyAuth",
                String::class.java,
                String::class.java,
                Continuation::class.java,
            )
            val cancellation = CancellationException("cancelled")
            mockStatic(binding).use { native ->
                native.`when`<Any?> { approve.invoke(null, "auth", "secret", null) }.thenThrow(cancellation)

                assertEquals(
                    cancellation.javaClass,
                    assertFailsWith<CancellationException> {
                        PubkyService(mock()).approveRingAuth("auth", "secret", 50.milliseconds)
                    }.javaClass,
                )
            }
        }
    }
}
