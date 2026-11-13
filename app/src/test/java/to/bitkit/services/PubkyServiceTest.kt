package to.bitkit.services

import com.synonym.paykit.EndpointSyncReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.Test
import org.mockito.kotlin.KInvocationOnMock
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.async.ServiceQueue
import to.bitkit.ext.runSuspendCatching
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PubkyServiceTest : BaseUnitTest() {
    @Test
    fun `paykit initialization does not hold the core queue`() = test {
        val paykit = mock<PaykitSdkService>()
        val sut = PubkyService(paykit)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        whenever(paykit.initialize()).doSuspendableAnswer {
            entered.countDown()
            release.await()
        }
        val initializing = launch { sut.initialize() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val coreReady = CountDownLatch(1)
            thread { ServiceQueue.CORE.blocking { coreReady.countDown() } }

            assertTrue(coreReady.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
        }
        initializing.join()
    }

    @Test
    fun `public cleanup reconciles disabled capabilities even after endpoint removal fails`() = test {
        for (failure in listOf(null, "endpoints", "registry")) {
            val paykit = mock<PaykitSdkService>()
            val sut = PubkyService(paykit)
            val expected = AppError("Cleanup failed")
            whenever(paykit.syncPublicEndpoints(emptyList())).thenAnswer {
                if (failure == "endpoints") throw expected
                EndpointSyncReport(published = emptyList(), removed = emptyList(), failed = emptyList())
            }
            whenever(paykit.syncPaykitApp(privatePaymentsEnabled = false)).thenAnswer {
                if (failure == "registry") throw expected
            }

            val result = runSuspendCatching { sut.removeBitkitPaymentEndpoints() }

            assertEquals(failure == null, result.isSuccess)
            if (failure != null) {
                assertEquals(expected.message, assertIs<AppError>(result.exceptionOrNull()).message)
            }
            inOrder(paykit).apply {
                verify(paykit).syncPublicEndpoints(emptyList())
                verify(paykit).syncPaykitApp(privatePaymentsEnabled = false)
            }
        }
    }

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
        val reads = listOf<suspend () -> Unit>(
            { sut.resolveContactProfile("pubky-test", allowPubkyProfileFallback = true) },
            { sut.fetchFile("pubky://pubky-test/avatar", 1uL) },
            { sut.getContacts("pubky-test") },
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
}
