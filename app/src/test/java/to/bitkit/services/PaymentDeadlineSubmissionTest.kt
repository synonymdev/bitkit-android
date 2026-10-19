package to.bitkit.services

import com.synonym.bitkitcore.onchainBroadcastRawTx
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.Test
import org.lightningdevkit.ldknode.Node
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import to.bitkit.async.ServiceQueue
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import to.bitkit.utils.ServiceError
import kotlin.coroutines.CoroutineContext
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
            StandardTestDispatcher(testScheduler),
        )

        for (isMax in listOf(false, true)) {
            now = deadline - 1.nanoseconds
            val result = async {
                runCatching { service.prepareOnchainSend("address", 1uL, 1uL, isMaxAmount = isMax, paymentDeadlineAt = deadline) }
            }
            now = deadline + 1.nanoseconds
            runCurrent()

            assertIs<ServiceError.PaymentDeadlineExpired>(result.await().exceptionOrNull())
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
            testDispatcher,
        )

        val error = runCatching {
            service.prepareOnchainSend("address", 1uL, 1uL, paymentDeadlineAt = deadline)
        }.exceptionOrNull()

        assertSame(submitted, error)
    }

    @Test
    fun `expiry after preparation prevents native dispatch of the retained candidate`() = test {
        var now = deadline
        val node = mock<Node>()
        val payment = mock<org.lightningdevkit.ldknode.OnchainPayment>()
        val native = mock<org.lightningdevkit.ldknode.PreparedOnchainSend>()
        whenever(node.onchainPayment()).thenReturn(payment)
        whenever(payment.prepareSendToAddress(org.mockito.kotlin.eq("address"), org.mockito.kotlin.eq(1uL),
            org.mockito.kotlin.any(), org.mockito.kotlin.isNull())).thenReturn(native)
        whenever(native.txid()).thenReturn("ab".repeat(32))
        whenever(native.inputs()).thenReturn(listOf(org.lightningdevkit.ldknode.OutPoint("11".repeat(32), 0u)))
        whenever(native.recipientAmountSats()).thenReturn(1uL)
        val service = lightningService(node, object : Clock { override fun now() = now }, testDispatcher)
        val prepared = service.prepareOnchainSend("address", 1uL, 1uL, paymentDeadlineAt = deadline)
        now = deadline + 1.nanoseconds

        val error = runCatching { prepared.broadcast() }.exceptionOrNull()

        assertIs<ServiceError.PaymentDeadlineExpired>(error)
        assertEquals("ab".repeat(32), prepared.receipt.txid)
        verify(native, never()).broadcast()
    }

    @Test
    fun `hardware broadcast checks inclusive deadline inside the CORE queue`() = test {
        var now = deadline
        val service = TrezorService(
            mock(),
            mock(),
            object : Clock {
                override fun now() = now
            },
        )
        withContext(ServiceQueue.CORE.queueContext) {
            mockStatic(Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt")).use { native ->
                native.`when`<String> {
                    runBlocking { onchainBroadcastRawTx("signed-tx", "electrum") }
                }.thenReturn("txid")
                assertEquals("txid", service.broadcastRawTx("signed-tx", "electrum", deadline))
                now = deadline + 1.nanoseconds

                val error = runCatching { service.broadcastRawTx("signed-tx", "electrum", deadline) }.exceptionOrNull()

                assertIs<ServiceError.PaymentDeadlineExpired>(error?.cause)
                native.verify { runBlocking { onchainBroadcastRawTx("signed-tx", "electrum") } }
                native.verifyNoMoreInteractions()
            }
        }
    }

    private fun lightningService(
        node: Node,
        clock: Clock,
        queue: CoroutineContext,
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
        onchainFeeRateFactory = { mock() },
    ).also { it.node = node }
}
