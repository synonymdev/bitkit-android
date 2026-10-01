package to.bitkit.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.KInvocationOnMock
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import to.bitkit.async.ServiceQueue
import to.bitkit.ext.runSuspendCatching
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
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
    fun `cancelling a public read cancels the underlying call`() = test {
        val paykit = mock<PaykitSdkService>()
        val sut = PubkyService(paykit)
        var started = CompletableDeferred<Unit>()
        var cancelled = CompletableDeferred<Boolean>()
        val pending: suspend KInvocationOnMock.() -> Nothing = {
            started.complete(Unit)
            try {
                delay(5.seconds)
            } finally {
                cancelled.complete(!currentCoroutineContext().isActive)
            }
            throw AppError("Read finished without cancellation")
        }
        whenever(paykit.resolveContactProfile("pubky-test", true)).doSuspendableAnswer(pending)
        whenever(paykit.fetchFile("pubky://pubky-test/avatar", 1uL)).doSuspendableAnswer(pending)
        whenever(paykit.fetchPubkyFollows("pubky-test")).doSuspendableAnswer(pending)
        whenever(paykit.discoverRelevantReceiverPaths("pubky-test")).doSuspendableAnswer(pending)
        val reads = listOf<suspend () -> Unit>(
            { sut.resolveContactProfile("pubky-test", allowPubkyProfileFallback = true) },
            { sut.fetchFile("pubky://pubky-test/avatar", 1uL) },
            { sut.getContacts("pubky-test") },
            { sut.discoverRelevantReceiverPaths("pubky-test") },
        )

        for (read in reads) {
            started = CompletableDeferred()
            cancelled = CompletableDeferred()
            val caller = launch { read() }
            started.await()

            caller.cancelAndJoin()

            assertTrue(cancelled.await())
        }
    }

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
            val sut = spy(PubkyService(mock()))
            doReturn("pubky-test").whenever(sut).publicKeyFromSecret("secret")
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
            val sut = spy(PubkyService(mock()))
            doReturn("pubky-test").whenever(sut).publicKeyFromSecret("secret")
            mockStatic(binding).use { native ->
                native.`when`<Any?> { approve.invoke(null, "auth", "secret", null) }.thenThrow(cancellation)

                assertEquals(
                    cancellation.javaClass,
                    assertFailsWith<CancellationException> {
                        sut.approveRingAuth("auth", "secret", 50.milliseconds)
                    }.javaClass,
                )
            }
        }
    }
}
