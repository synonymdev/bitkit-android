package to.bitkit.services

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.Test
import org.lightningdevkit.ldknode.Node
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import to.bitkit.async.ServiceQueue
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import to.bitkit.utils.ServiceError
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentDeadlineSubmissionTest : BaseUnitTest() {
    private val deadline = Instant.parse("2026-10-06T12:00:00.123456789Z")

    @Test
    fun `onchain deadline is rechecked after waiting in the LDK queue`() = test {
        val node = mock<Node>()
        var now = deadline - 1.nanoseconds
        val service = lightningService(
            node,
            object : Clock {
                override fun now() = now
            },
            StandardTestDispatcher(testScheduler)
        )

        for (isMax in listOf(false, true)) {
            now = deadline - 1.nanoseconds
            val result = async {
                runCatching { service.send("address", 1uL, 1uL, isMaxAmount = isMax, paymentDeadlineAt = deadline) }
            }
            now = deadline + 1.nanoseconds
            runCurrent()

            assertIs<ServiceError.PaymentDeadlineExpired>(result.await().exceptionOrNull()?.cause)
        }
        verifyNoInteractions(node)
    }

    @Test
    fun `onchain submission permits the exact deadline`() = test {
        val node = mock<Node>()
        val submitted = IllegalStateException("reached node")
        whenever(node.onchainPayment()).thenThrow(submitted)
        val service = lightningService(
            node,
            object : Clock {
                override fun now() = deadline
            },
            testDispatcher
        )

        val error = runCatching {
            service.send("address", 1uL, 1uL, paymentDeadlineAt = deadline)
        }.exceptionOrNull()

        assertSame(submitted, assertIs<AppError>(error).cause)
    }

    @Test
    fun `hardware broadcast checks inclusive deadline inside the CORE queue`() = test {
        var now = deadline
        val service = TrezorService(
            mock(),
            mock(),
            object : Clock {
                override fun now() = now
            }
        )
        withContext(ServiceQueue.CORE.queueContext) {
            mockStatic(Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt")).use { native ->
                native.`when`<String> {
                    runBlocking { com.synonym.bitkitcore.onchainBroadcastRawTx("signed-tx", "electrum") }
                }.thenReturn("txid")
                assertEquals("txid", service.broadcastRawTx("signed-tx", "electrum", deadline))
                now = deadline + 1.nanoseconds

                val error = runCatching { service.broadcastRawTx("signed-tx", "electrum", deadline) }.exceptionOrNull()

                assertIs<ServiceError.PaymentDeadlineExpired>(error?.cause)
                native.verify { runBlocking { com.synonym.bitkitcore.onchainBroadcastRawTx("signed-tx", "electrum") } }
                native.verifyNoMoreInteractions()
            }
        }
    }

    private fun lightningService(
        node: Node,
        clock: Clock,
        queue: kotlin.coroutines.CoroutineContext,
    ) = LightningService(
        bgDispatcher = testDispatcher,
        ioDispatcher = testDispatcher,
        keychain = mock(),
        vssStoreIdProvider = mock(),
        settingsStore = mock(),
        watchOnlyAccountStore = mock(),
        loggerLdk = mock(),
        watchOnlyAccountLifecycleCoordinator = WatchOnlyAccountLifecycleCoordinator(),
        ldkQueue = queue,
        clock = clock,
    ).also { it.node = node }
}
