package to.bitkit.services.offline

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.env.Env
import to.bitkit.models.OfflineReceiveDevSettings
import to.bitkit.services.OfflineInvoiceDetails
import to.bitkit.services.OfflineReceiveInvoiceParser
import to.bitkit.services.OfflineReceiveRequest
import to.bitkit.services.OfflineReceiveUnavailable
import to.bitkit.services.offline.OfflineReceiveClientException.Kind
import to.bitkit.services.offline.OfflineReceiveClientStatus.Expired
import to.bitkit.services.offline.OfflineReceiveClientStatus.Failed
import to.bitkit.services.offline.OfflineReceiveClientStatus.Pending
import to.bitkit.services.offline.OfflineReceiveClientStatus.Ready
import to.bitkit.services.offline.OfflineReceiveClientStatus.Settled
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LdkOfflineReceiveServiceTest : BaseUnitTest() {
    private val client = FakeOfflineReceiveClient()
    private val store = InMemoryOfflineReceiveRequestStore()
    private val invoiceParser = mock<OfflineReceiveInvoiceParser>()
    private val devSettings = MutableStateFlow(OfflineReceiveDevSettings(isEnabled = true))
    private val settingsSource = OfflineReceiveSettingsSource(devSettings, isNativeAvailable = true)
    private val request = OfflineReceiveRequest("request-1", 1_000uL, "Dinner")
    private val details = OfflineInvoiceDetails(
        amountMsat = 1_000_000uL,
        description = "Dinner",
        network = Env.network,
        timestampSeconds = 2_000_000_000uL,
        expirySeconds = 3_600uL,
        paymentHash = "hash",
        payeePubkey = "node",
    )
    private var clientAvailable = true
    private lateinit var sut: LdkOfflineReceiveService

    @Before
    fun setUp() {
        whenever(invoiceParser.parse("bolt11-ready")).thenReturn(details)
        sut = newService()
    }

    private fun newService() = LdkOfflineReceiveService(
        clientProvider = { if (clientAvailable) client else null },
        requestStore = store,
        invoiceParser = invoiceParser,
        settingsSource = settingsSource,
        ioDispatcher = testDispatcher,
        pollIntervalMillis = 100L,
    )

    @Test
    fun `ready status returns the validated invoice and keeps the request identity`() = test {
        client.script("request-1", Pending("AwaitingActivation"), Ready("bolt11-ready"))

        val invoice = sut.prepareInvoice(request).getOrThrow()

        assertEquals("bolt11-ready", invoice.bolt11)
        assertEquals(1_000uL, invoice.amountSats)
        assertEquals("Dinner", invoice.description)
        assertEquals("hash", invoice.paymentHash)
        assertEquals(2_000_003_600_000L, invoice.expiresAtMillis)
        assertEquals(listOf(Triple("request-1", 1_000_000uL, "Dinner")), client.prepareCalls)
        assertEquals(PersistedOfflineReceiveRequest("request-1", 1_000uL, "Dinner"), store.request)
        assertTrue(client.cancelCalls.isEmpty())
    }

    @Test
    fun `timeout fails without cancelling and keeps the identity for resume`() = test {
        devSettings.value = OfflineReceiveDevSettings(isEnabled = true, prepareTimeoutMillis = 1_000L)

        val result = sut.prepareInvoice(request)

        assertIs<OfflineReceiveUnavailable>(result.exceptionOrNull())
        assertEquals("request-1", store.request?.requestId)
        assertTrue(client.cancelCalls.isEmpty())
        assertTrue(client.statusCalls.count { it == "request-1" } >= 2)
    }

    @Test
    fun `terminal statuses fail and clear the identity`() = test {
        val terminal = listOf(Failed("reason"), Expired, Settled(fulfilled = true), Settled(fulfilled = false))
        terminal.forEachIndexed { index, status ->
            val requestId = "terminal-$index"
            client.script(requestId, Pending("Preparing"), status)

            val result = sut.prepareInvoice(request.copy(requestId = requestId))

            assertIs<OfflineReceiveUnavailable>(result.exceptionOrNull(), "status $status")
            assertNull(store.request, "status $status")
        }
        assertTrue(client.cancelCalls.isEmpty())
    }

    @Test
    fun `same intent after restart recovers a ready invoice under the persisted request id`() = test {
        client.script("request-1", Ready("bolt11-ready"))
        sut.prepareInvoice(request).getOrThrow()
        assertEquals(1, client.prepareCalls.size)

        sut = newService()
        val invoice = sut.prepareInvoice(request.copy(requestId = "request-2")).getOrThrow()

        assertEquals("bolt11-ready", invoice.bolt11)
        assertEquals(1, client.prepareCalls.size)
        assertEquals("request-1", store.request?.requestId)
        assertTrue(client.statusCalls.contains("request-1"))
        assertFalse(client.statusCalls.contains("request-2"))
        assertTrue(client.cancelCalls.isEmpty())
    }

    @Test
    fun `same intent after restart resumes a pending request with the persisted request id`() = test {
        client.script("request-1", Pending("AwaitingWitnesses"), Pending("AwaitingWitnesses"), Ready("bolt11-ready"))
        sut.prepareInvoice(request).getOrThrow()

        sut = newService()
        // After a restart the library reports the request but has not released its invoice again.
        client.script("request-1", Pending("AwaitingWitnesses"), Pending("AwaitingWitnesses"), Ready("bolt11-ready"))
        val invoice = sut.prepareInvoice(request.copy(requestId = "request-2")).getOrThrow()

        assertEquals("bolt11-ready", invoice.bolt11)
        assertEquals(listOf("request-1", "request-1"), client.prepareCalls.map { it.first })
        assertEquals("request-1", store.request?.requestId)
    }

    @Test
    fun `different intent cancels the superseded request and persists the new identity`() = test {
        client.script("request-1", Ready("bolt11-ready"))
        sut.prepareInvoice(request).getOrThrow()
        whenever(invoiceParser.parse("bolt11-ready-2")).thenReturn(details.copy(amountMsat = 2_000_000uL))
        client.script("request-2", Ready("bolt11-ready-2"))

        val invoice = sut.prepareInvoice(OfflineReceiveRequest("request-2", 2_000uL, "Dinner")).getOrThrow()

        assertEquals("bolt11-ready-2", invoice.bolt11)
        assertEquals(listOf("request-1"), client.cancelCalls)
        assertEquals(PersistedOfflineReceiveRequest("request-2", 2_000uL, "Dinner"), store.request)
        assertEquals("request-2", client.prepareCalls.last().first)
    }

    @Test
    fun `persisted request the library no longer tracks is replaced without cancel`() = test {
        store.request = PersistedOfflineReceiveRequest("request-0", 1_000uL, "Dinner")
        client.script("request-1", Ready("bolt11-ready"))

        sut.prepareInvoice(request).getOrThrow()

        assertTrue(client.cancelCalls.isEmpty())
        assertEquals("request-1", store.request?.requestId)
        assertEquals(listOf("request-1"), client.prepareCalls.map { it.first })
    }

    @Test
    fun `persisted terminal request is replaced without cancel`() = test {
        store.request = PersistedOfflineReceiveRequest("request-0", 1_000uL, "Dinner")
        client.track("request-0")
        client.script("request-0", Expired)
        client.script("request-1", Ready("bolt11-ready"))

        sut.prepareInvoice(request).getOrThrow()

        assertTrue(client.cancelCalls.isEmpty())
        assertEquals("request-1", store.request?.requestId)
    }

    @Test
    fun `same request id with a different intent fails before preparing`() = test {
        store.request = PersistedOfflineReceiveRequest("request-1", 5_000uL, "Other")

        val result = sut.prepareInvoice(request)

        assertIs<OfflineReceiveUnavailable>(result.exceptionOrNull())
        assertIs<OfflineReceiveClientException>(result.exceptionOrNull()?.cause).also {
            assertEquals(Kind.REQUEST_CONFLICT, it.kind)
        }
        assertTrue(client.prepareCalls.isEmpty())
    }

    @Test
    fun `caller cancellation keeps the identity and does not cancel the library request`() = test {
        val pending = async { sut.prepareInvoice(request) }
        testScheduler.advanceTimeBy(350L)

        pending.cancelAndJoin()

        assertTrue(pending.isCancelled)
        assertEquals("request-1", store.request?.requestId)
        assertTrue(client.cancelCalls.isEmpty())
        assertEquals(1, client.prepareCalls.size)
    }

    @Test
    fun `status errors during polling fail and keep the identity`() = test {
        client.statusError = OfflineReceiveClientException(Kind.NOT_RUNNING)

        val result = sut.prepareInvoice(request)

        assertIs<OfflineReceiveUnavailable>(result.exceptionOrNull())
        assertEquals("request-1", store.request?.requestId)
        assertTrue(client.cancelCalls.isEmpty())
    }

    @Test
    fun `invoice that does not match the request is rejected and the request cancelled`() = test {
        whenever(invoiceParser.parse("bolt11-ready")).thenReturn(details.copy(amountMsat = 999_000uL))
        client.script("request-1", Ready("bolt11-ready"))

        val result = sut.prepareInvoice(request)

        assertIs<OfflineReceiveUnavailable>(result.exceptionOrNull())
        assertEquals(listOf("request-1"), client.cancelCalls)
        assertNull(store.request)
    }

    @Test
    fun `invoice from another payee is rejected`() = test {
        whenever(invoiceParser.parse("bolt11-ready")).thenReturn(details.copy(payeePubkey = "other"))
        client.script("request-1", Ready("bolt11-ready"))

        assertIs<OfflineReceiveUnavailable>(sut.prepareInvoice(request).exceptionOrNull())
    }

    @Test
    fun `preparation is unavailable while the toggle is off or the node is missing`() = test {
        devSettings.value = OfflineReceiveDevSettings(isEnabled = false)
        assertIs<OfflineReceiveUnavailable>(sut.prepareInvoice(request).exceptionOrNull())

        devSettings.value = OfflineReceiveDevSettings(isEnabled = true)
        clientAvailable = false
        assertIs<OfflineReceiveUnavailable>(sut.prepareInvoice(request).exceptionOrNull())

        assertTrue(client.prepareCalls.isEmpty())
        assertNull(store.request)
    }

    @Test
    fun `canReceive maps library rejections to false and other errors to failure`() = test {
        assertTrue(sut.canReceive(1_000uL).getOrThrow())
        assertFalse(sut.canReceive(0uL).getOrThrow())

        client.canReceiveResult = false
        assertFalse(sut.canReceive(1_000uL).getOrThrow())

        listOf(Kind.DISABLED, Kind.UNAVAILABLE, Kind.INELIGIBLE, Kind.NOT_RUNNING).forEach { kind ->
            client.canReceiveError = OfflineReceiveClientException(kind)
            assertFalse(sut.canReceive(1_000uL).getOrThrow(), "kind $kind")
        }

        client.canReceiveError = OfflineReceiveClientException(Kind.OTHER)
        assertTrue(sut.canReceive(1_000uL).isFailure)

        client.canReceiveError = null
        client.canReceiveResult = true
        devSettings.value = OfflineReceiveDevSettings(isEnabled = false)
        assertFalse(sut.canReceive(1_000uL).getOrThrow())

        devSettings.value = OfflineReceiveDevSettings(isEnabled = true)
        clientAvailable = false
        assertFalse(sut.canReceive(1_000uL).getOrThrow())
    }

    @Test
    fun `cancelActiveRequest cancels the persisted request and forgets it`() = test {
        client.script("request-1", Ready("bolt11-ready"))
        sut.prepareInvoice(request).getOrThrow()

        sut.cancelActiveRequest().getOrThrow()

        assertEquals(listOf("request-1"), client.cancelCalls)
        assertNull(store.request)
        assertTrue(sut.cancelActiveRequest().isSuccess)
        assertEquals(1, client.cancelCalls.size)
    }
}
