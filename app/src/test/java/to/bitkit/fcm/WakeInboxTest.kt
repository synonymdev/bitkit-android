package to.bitkit.fcm

import com.synonym.bitkitcore.WakeAckOutcome
import com.synonym.bitkitcore.WakeEnvelope
import com.synonym.bitkitcore.WakeException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.WakeCredentialStore
import to.bitkit.data.WakeStore
import to.bitkit.data.WakeStoreData
import to.bitkit.repositories.WakeConfig
import to.bitkit.services.WakeCredentials
import to.bitkit.services.WakeService
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.InMemoryDataStore
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class WakeInboxTest : BaseUnitTest() {
    companion object {
        private const val GATEWAY_URL = "http://127.0.0.1:9020"
        private const val WAKE_ID = "01a0c450-6c00-7000-8000-000000000001"
        private const val NOW_SECS = 1_790_000_000L

        /** The v1 container of the `fcm_v1` case in core's `test_vectors/pushes.json`. */
        private const val FCM_V1_CONTAINER = "{\"v\":1,\"cipher\":\"bXIadw8XhVApCI59oieKY3P/h33PZz6NSdVFABqcg9CSgN" +
            "hH5L+odPp6fRlxsbx/A37B0ZTxkuZgONflwXB4AyprdoQGEuDEbM8NSRT1j9uAzrfBR098QxyA5eA4nsKbginEF+oPHY97X0yzHZ" +
            "wxV7qd69X4SKve9PaXn4li383A1ZTqlcXJhtAWA7U5YHiFFrbiHAoQgUr7D01/z0vPKtHvryG+GD0rtFnrIzNwrwt6MH9hGO8gYH" +
            "p1xPRO0bs432EnkOUO7rRv0JwD6q1VQ+GJrtEp/n8p5tW3QzOd8cexBUGoQM+Q6HlNHFRROjBM1fTIsaZj\",\"iv\":\"000102" +
            "030405060708090a0b\",\"tag\":\"4521e07642272d9b8aed5157c331ad7a\",\"publicKey\":\"035ab4689e400a4a16" +
            "0cf01cd44730845a54768df8547dcdf073d964f109f18c30\"}"

        /** The data map of the `fcm_fallback` case in core's `test_vectors/pushes.json`. */
        private const val FCM_FALLBACK = "{\"v\":1,\"id\":\"01a0c450-6c00-7000-8000-000000000001\"," +
            "\"topic\":\"blocktank.incomingHtlc\"}"

        private val PUSH_JSON = wakePushJson(mapOf("wake" to FCM_V1_CONTAINER))

        private val CREDENTIALS = WakeCredentials(
            installId = "AAAAAAAAAAAAAAAAAAAAAA",
            encryptionSecretKey = "55".repeat(32),
            encryptionPublicKey = "02" + "9a".repeat(32),
            deviceSecret = "wkd_test_secret",
            deviceSecretSha256 = "a4".repeat(32),
        )
    }

    private val wakeService = mock<WakeService>()
    private val wakeConfig = mock<WakeConfig>()
    private val credentialStore = mock<WakeCredentialStore>()
    private val wakeStore = WakeStore(InMemoryDataStore(WakeStoreData()))
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(NOW_SECS)
    }
    private var handledCount = 0
    private var handlerFailure: Throwable? = null
    private val testHandler = WakeHandler {
        handledCount++
        handlerFailure?.let { throw it }
    }

    private lateinit var sut: WakeInbox

    @Before
    fun setUp() {
        whenever(wakeConfig.gatewayUrl).thenReturn(GATEWAY_URL)
        whenever { credentialStore.load() }.thenReturn(CREDENTIALS)
        whenever { wakeService.ack(any(), any(), any(), any()) }.thenReturn(Unit)
        sut = createSut()
    }

    private fun createSut() = WakeInbox(
        ioDispatcher = testDispatcher,
        wakeService = wakeService,
        wakeConfig = wakeConfig,
        credentialStore = credentialStore,
        wakeStore = wakeStore,
        handlers = WakeHandlers(mapOf(WakeHandlers.TEST_TOPIC to testHandler)),
        clock = clock,
    )

    @Test
    fun `wakePushJson serializes the FCM data map the way the gateway sends it`() {
        assertEquals(JsonObject(mapOf("wake" to JsonPrimitive(FCM_V1_CONTAINER))), Json.parseToJsonElement(PUSH_JSON))
        assertTrue(PUSH_JSON.startsWith("{\"wake\":\"{\\\"v\\\":1,"), "Compact, with the container as a string")

        val fallback = Json.parseToJsonElement(wakePushJson(mapOf("wake_fallback" to FCM_FALLBACK)))
        assertEquals(JsonObject(mapOf("wake_fallback" to JsonPrimitive(FCM_FALLBACK))), fallback)
    }

    @Test
    fun `a wake delivered twice does its work once`() = test {
        stubPush(envelope())

        val first = sut.receive(PUSH_JSON).getOrThrow()
        val second = sut.receive(PUSH_JSON).getOrThrow()
        val afterRestart = createSut().receive(PUSH_JSON).getOrThrow()

        assertEquals(WakeReceipt.Acked(WakeAckOutcome.HANDLED), first)
        assertEquals(WakeReceipt.Duplicate, second)
        assertEquals(WakeReceipt.Duplicate, afterRestart, "Seen wakes outlive the process")
        assertEquals(1, handledCount)
        verify(wakeService, times(1)).ack(GATEWAY_URL, CREDENTIALS.deviceSecret, WAKE_ID, WakeAckOutcome.HANDLED)
    }

    @Test
    fun `a wake past its deadline is dropped without its work or an ack`() = test {
        stubPush(envelope(deadline = (NOW_SECS - 1).toULong()))

        val receipt = sut.receive(PUSH_JSON).getOrThrow()

        assertEquals(WakeReceipt.Stale, receipt)
        assertEquals(0, handledCount)
        verify(wakeService, never()).ack(any(), any(), any(), any())
    }

    @Test
    fun `a wake is acked handled when its work is done`() = test {
        stubPush(envelope())

        assertEquals(WakeReceipt.Acked(WakeAckOutcome.HANDLED), sut.receive(PUSH_JSON).getOrThrow())
    }

    @Test
    fun `a wake is acked failed when its work failed`() = test {
        handlerFailure = AppError("Notifications are off")
        stubPush(envelope())

        assertEquals(WakeReceipt.Acked(WakeAckOutcome.FAILED), sut.receive(PUSH_JSON).getOrThrow())
        verify(wakeService).ack(GATEWAY_URL, CREDENTIALS.deviceSecret, WAKE_ID, WakeAckOutcome.FAILED)
    }

    @Test
    fun `a wake of a topic without a handler is acked no handler`() = test {
        stubPush(envelope(topic = "paykit.sync"))

        assertEquals(WakeReceipt.Acked(WakeAckOutcome.NO_HANDLER), sut.receive(PUSH_JSON).getOrThrow())
        assertEquals(0, handledCount)
        verify(wakeService).ack(GATEWAY_URL, CREDENTIALS.deviceSecret, WAKE_ID, WakeAckOutcome.NO_HANDLER)
    }

    @Test
    fun `an ack that did not reach the gateway is sent again without the work`() = test {
        stubPush(envelope())
        var ackAttempts = 0
        whenever(wakeService.ack(any(), any(), any(), any())).thenAnswer {
            ackAttempts++
            if (ackAttempts == 1) throw AppError(WakeException.RequestFailed("offline"))
            Unit
        }

        val first = sut.receive(PUSH_JSON)
        val retry = sut.receive(PUSH_JSON).getOrThrow()

        assertTrue(first.isFailure)
        assertEquals(WakeReceipt.Acked(WakeAckOutcome.HANDLED), retry)
        assertEquals(1, handledCount)
        assertEquals(2, ackAttempts)
    }

    @Test
    fun `an ack the gateway refuses for good is not sent again`() = test {
        stubPush(envelope())
        whenever(wakeService.ack(any(), any(), any(), any())).thenAnswer {
            throw AppError(WakeException.GatewayRejected(404u, "unknown_wake", "unknown wake", null, null))
        }

        assertEquals(WakeReceipt.Acked(WakeAckOutcome.HANDLED), sut.receive(PUSH_JSON).getOrThrow())
        assertEquals(WakeReceipt.Duplicate, sut.receive(PUSH_JSON).getOrThrow())
        verify(wakeService, times(1)).ack(any(), any(), any(), any())
    }

    @Test
    fun `a fallback alert has nothing to do or ack`() = test {
        stubPush(envelope(fallback = true))

        assertEquals(WakeReceipt.NothingToAck, sut.receive(PUSH_JSON).getOrThrow())
        assertEquals(0, handledCount)
        verify(wakeService, never()).ack(any(), any(), any(), any())
    }

    @Test
    fun `a push that cannot be decrypted is dropped`() = test {
        whenever(wakeService.decryptPush(CREDENTIALS.encryptionSecretKey, PUSH_JSON)).thenAnswer {
            throw AppError(WakeException.DecryptionFailed("tag mismatch"))
        }

        assertEquals(WakeReceipt.Undecryptable, sut.receive(PUSH_JSON).getOrThrow())
        verify(wakeService, never()).ack(any(), any(), any(), any())
    }

    @Test
    fun `a push is ignored while wakes are off`() = test {
        whenever(wakeConfig.gatewayUrl).thenReturn(null)

        assertEquals(WakeReceipt.Ignored, sut.receive(PUSH_JSON).getOrThrow())
        verify(wakeService, never()).decryptPush(any(), any())
    }

    private suspend fun stubPush(envelope: WakeEnvelope) {
        whenever(wakeService.decryptPush(CREDENTIALS.encryptionSecretKey, PUSH_JSON)).thenReturn(envelope)
    }

    private fun envelope(
        topic: String = WakeHandlers.TEST_TOPIC,
        deadline: ULong = (NOW_SECS + 300).toULong(),
        fallback: Boolean = false,
    ) = WakeEnvelope(
        version = 1u,
        id = WAKE_ID,
        topic = topic,
        urgency = "alert",
        deadline = deadline,
        payloadJson = "{\"message\":\"test\"}",
        createdAt = null,
        fallback = fallback,
    )
}
