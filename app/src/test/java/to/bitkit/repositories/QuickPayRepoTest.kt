package to.bitkit.repositories

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.NodeException
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.di.json
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.USD
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.LdkError
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@Config(application = Application::class, sdk = [34])
@RunWith(RobolectricTestRunner::class)
@Suppress("LargeClass")
class QuickPayRepoTest : BaseUnitTest() {
    companion object {
        private const val TEST_BOLT11 = "lnbcrt1quickpay"
        private const val TEST_HASH = "quickpay-invoice-hash"
        private val IOS_LEDGER_JSON = """
            {
              "version": 1,
              "dayKey": "2026-08-15",
              "spentCents": 250,
              "records": [
                {
                  "id": "rec-ios",
                  "amountCents": 250,
                  "dayKey": "2026-08-15",
                  "invoicePaymentHash": "inv-ios",
                  "phase": "submitting"
                }
              ]
            }
        """.trimIndent()
        private val ANDROID_LEDGER_JSON = """
            {
              "version": 1,
              "dayKey": "2026-08-15",
              "spentCents": 500,
              "records": [
                {
                  "id": "rec-android",
                  "amountCents": 250,
                  "dayKey": "2026-08-15",
                  "invoicePaymentHash": "inv-android",
                  "paymentId": "pid-android",
                  "phase": "submitted"
                },
                {
                  "id": "rec-android-2",
                  "amountCents": 250,
                  "dayKey": "2026-08-15",
                  "invoicePaymentHash": "inv-android-2",
                  "paymentId": null,
                  "phase": "submitting"
                }
              ]
            }
        """.trimIndent()
    }
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cacheStore = CacheStore(context)
    private val settingsStore: SettingsStore = mock()
    private val currencyRepo: CurrencyRepo = mock()
    private val lightningRepo: LightningRepo = mock()
    private val pendingPaymentRepo: PendingPaymentRepo = mock()
    private val clock = MutableClock(Instant.parse("2026-08-15T12:00:00Z"))
    private val settingsData = MutableStateFlow(
        SettingsData(isQuickPayEnabled = true, quickPayAmount = 5, quickPayDailyLimitMultiplier = 5),
    )
    private val lightningState = MutableStateFlow(LightningState())
    private var paymentRows: List<QuickPayReconcileRow>? = null

    private lateinit var sut: QuickPayRepo

    @Before
    fun setUp() = runBlocking {
        cacheStore.reset()
        paymentRows = null
        whenever(settingsStore.data).thenReturn(settingsData)
        whenever(lightningRepo.lightningState).thenReturn(lightningState)
        whenever { lightningRepo.listPaymentsOrNull() }.thenReturn(null)
        whenever(currencyRepo.convertFiatToSats(5.0, USD)).thenAnswer { 1000uL }
        whenever(currencyRepo.convertSatsToFiat(any(), anyOrNull())).thenAnswer { invocation ->
            val sats = invocation.getArgument<Long>(0)
            val usd = 5.0 * sats.toDouble() / 1000.0
            ConvertedAmount(
                value = BigDecimal.valueOf(usd),
                formatted = usd.toString(),
                symbol = "$",
                currency = "USD",
                flag = "",
                sats = sats,
                locale = Locale.US,
            )
        }
        sut = repo()
    }

    @After
    fun tearDown() = runBlocking { cacheStore.reset() }

    @Test
    fun `reserveBound on clock rollback keeps existing spend`() = test {
        assertNotNull(sut.reserveBound("a", 500u).getOrThrow())
        clock.instant = Instant.parse("2026-08-14T12:00:00Z")

        assertNotNull(sut.reserveBound("b", 200u).getOrThrow())
        assertEquals(350L, spentCents())
        clock.instant = Instant.parse("2026-08-15T12:00:00Z")
        assertEquals(350L, spentCents())
    }

    @Test
    fun `reserveBound accumulates on the same day and resets on a new day`() = test {
        assertNotNull(sut.reserveBound("a", 400u).getOrThrow())
        assertNotNull(sut.reserveBound("b", 300u).getOrThrow())
        assertEquals(350L, spentCents())

        clock.instant = Instant.parse("2026-08-16T12:00:00Z")
        assertNotNull(sut.reserveBound("c", 800u).getOrThrow())
        assertEquals(400L, spentCents())
    }

    @Test
    fun `reserveBound reserves under the cap and rejects over it`() = test {
        settingsData.value = settingsData.value.copy(quickPayDailyLimitMultiplier = 2)
        assertNotNull(sut.reserveBound("a", 1000u).getOrThrow())
        assertNotNull(sut.reserveBound("b", 1000u).getOrThrow())
        assertNull(sut.reserveBound("c", 1000u).getOrThrow())
        assertEquals(1000L, spentCents())
    }

    @Test
    fun `reserveBound rejects a duplicate invoice hash`() = test {
        assertNotNull(sut.reserveBound("abc", 1000u).getOrThrow())
        assertNull(sut.reserveBound("abc", 1000u).getOrThrow())
    }

    @Test
    fun `signalCompletion failure rolls back a reservation`() = test {
        assertNotNull(sut.reserveBound("abc", 1000u).getOrThrow())
        markSubmitted("abc", "pid")

        val outcome = sut.signalCompletion(paymentId = "pid", paymentHash = "abc", success = false)

        assertEquals(QuickPayCompletionKind.SETTLED_FAILURE, outcome.kind)
        assertEquals(0L, spentCents())
        assertTrue(cacheStore.data.first().quickPayLedger!!.records.isEmpty())
    }

    @Test
    fun `signalCompletion failure on a prior day does not decrement the new day`() = test {
        assertNotNull(sut.reserveBound("old", 1000u).getOrThrow())
        markSubmitted("old", "old-pid")
        clock.instant = Instant.parse("2026-08-16T12:00:00Z")
        assertNotNull(sut.reserveBound("new", 800u).getOrThrow())

        sut.signalCompletion(paymentId = "old-pid", paymentHash = "old", success = false)

        assertEquals(400L, spentCents())
    }

    @Test
    fun `signalCompletion success keeps spend`() = test {
        assertNotNull(sut.reserveBound("abc", 1000u).getOrThrow())

        val outcome = sut.signalCompletion(paymentId = "pid", paymentHash = "abc", success = true)

        assertEquals(QuickPayCompletionKind.SETTLED_SUCCESS, outcome.kind)
        assertEquals(500L, spentCents())
        assertTrue(cacheStore.data.first().quickPayLedger!!.records.isEmpty())
    }

    @Test
    fun `signalCompletion is idempotent`() = test {
        assertNotNull(sut.reserveBound("abc", 1000u).getOrThrow())
        sut.signalCompletion(paymentId = null, paymentHash = "abc", success = true)

        val second = sut.signalCompletion(paymentId = null, paymentHash = "abc", success = true)

        assertEquals(QuickPayCompletionOutcome.None, second)
        assertEquals(500L, spentCents())
    }

    @Test
    fun `dual aliases settle one record`() = test {
        assertNotNull(sut.reserveBound("inv", 1000u).getOrThrow())
        sut.signalCompletion(paymentId = "pid", paymentHash = "other", success = true)
        // paymentId was not stored yet; settle by invoice hash then alias
        val first = sut.signalCompletion(paymentId = "pid", paymentHash = "inv", success = true)
        val second = sut.signalCompletion(paymentId = "pid", paymentHash = "inv", success = false)

        assertEquals(QuickPayCompletionKind.SETTLED_SUCCESS, first.kind)
        assertEquals(QuickPayCompletionOutcome.None, second)
    }

    @Test
    fun `unattributable failed event against submitting retains`() = test {
        assertNotNull(sut.reserveBound("inv", 1000u).getOrThrow())

        val outcome = sut.signalCompletion(paymentId = "stale-pid", paymentHash = "other", success = false)

        assertEquals(QuickPayCompletionOutcome.None, outcome)
        assertEquals(500L, spentCents())
        assertEquals(1, cacheStore.data.first().quickPayLedger!!.records.size)
    }

    @Test
    fun `canApply is true under threshold and cap`() = test {
        assertTrue(sut.canApply(500u).getOrThrow())
    }

    @Test
    fun `canApply is false when daily cap would be exceeded`() = test {
        settingsData.value = settingsData.value.copy(quickPayDailyLimitMultiplier = 1)
        assertNotNull(sut.reserveBound("a", 1000u).getOrThrow())

        assertFalse(sut.canApply(1000u).getOrThrow())
    }

    @Test
    fun `canApply is false when disabled`() = test {
        settingsData.value = settingsData.value.copy(isQuickPayEnabled = false)

        assertFalse(sut.canApply(500u).getOrThrow())
    }

    @Test
    fun `zero cent conversion at a full cap is rejected`() = test {
        stubZeroCentConversion(7L)
        repeat(5) { assertNotNull(sut.reserveBound("h$it", 1000u).getOrThrow()) }

        assertFalse(sut.canApply(7u).getOrThrow())
        assertNull(sut.reserveBound("dust", 7u).getOrThrow())
    }

    @Test
    fun `zero cent conversion on a fresh day reserves one cent`() = test {
        stubZeroCentConversion(7L)

        val reserved = requireNotNull(sut.reserveBound("dust", 7u).getOrThrow())

        assertEquals(1L, reserved.amountCents)
        assertEquals(1L, spentCents())
        assertTrue(sut.canApply(7u).getOrThrow())
    }

    @Test
    fun `reserveBound fails with conversion error when rates are unavailable`() = test {
        whenever(currencyRepo.convertSatsToFiat(any(), anyOrNull())).thenAnswer {
            throw QuickPayConversionError()
        }

        val result = sut.reserveBound("abc", 500u)

        assertTrue(result.exceptionOrNull() is QuickPayConversionError)
    }

    @Test
    fun `fresh repo does not reserve the same recovered hash`() = test {
        assertNotNull(sut.reserveBound("inv", 1000u).getOrThrow())
        val reloaded = repo()

        assertNull(reloaded.reserveBound("inv", 1000u).getOrThrow())
        verify(lightningRepo, never()).payInvoice(any(), anyOrNull(), any())
    }

    @Test
    fun `ios ledger fixture decodes`() {
        val ledger = json.decodeFromString<QuickPayLedger>(IOS_LEDGER_JSON)
        assertEquals(1, ledger.version)
        assertEquals("inv-ios", ledger.records.single().invoicePaymentHash)
        assertEquals(QuickPayRecordPhase.SUBMITTING, ledger.records.single().phase)
        assertNull(ledger.records.single().paymentId)
    }

    @Test
    fun `android ledger fixture decodes`() {
        val ledger = json.decodeFromString<QuickPayLedger>(ANDROID_LEDGER_JSON)
        assertEquals("pid-android", ledger.records.first().paymentId)
        assertEquals(QuickPayRecordPhase.SUBMITTED, ledger.records.first().phase)
        assertEquals(QuickPayRecordPhase.SUBMITTING, ledger.records.last().phase)
    }

    @Test
    fun `unsupported ledger version does not wipe unrelated cache`() = test {
        cacheStore.update {
            AppCacheData(
                onchainAddress = "keep-me",
                paidOrders = mapOf("order" to "tx"),
                quickPayLedger = QuickPayLedger(
                    version = 99,
                    dayKey = "2026-08-15",
                    spentCents = 999L,
                    records = emptyList(),
                ),
            )
        }

        assertNull(sut.reserveBound("x", 1000u).getOrThrow())
        assertFalse(sut.canApply(500u).getOrThrow())
        val data = cacheStore.data.first()
        assertEquals("keep-me", data.onchainAddress)
        assertEquals(mapOf("order" to "tx"), data.paidOrders)
        assertEquals(99, data.quickPayLedger?.version)
        assertEquals(999L, data.quickPayLedger?.spentCents)
    }

    @Test
    fun `lookup throw on duplicate still emits pending`() = test {
        val (bolt11, _) = testInvoice()
        stubPayInvoiceFailure(NodeException.DuplicatePayment("dup"))
        whenever { lightningRepo.listPaymentsOrNull() }.thenAnswer { error("uniffi") }
        val session = QuickPaySession()
        sut.attach(session).test {
            sut.payNow(session, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
            assertIs<QuickPaySessionEvent.Pending>(awaitItem())
        }
        assertEquals(250L, spentCents())
        assertEquals(1, cacheStore.data.first().quickPayLedger!!.records.size)
    }

    @Test
    fun `live record survives day prune then settles`() = test {
        val (bolt11, hash) = testInvoice()
        val dispatched = CompletableDeferred<Unit>()
        whenever { lightningRepo.payInvoice(any(), anyOrNull(), any()) }.doSuspendableAnswer { invocation ->
            val onBeforeSend = invocation.getArgument<suspend () -> Boolean>(2)
            if (!onBeforeSend()) return@doSuspendableAnswer Result.failure(PaymentAbortedBeforeSend())
            dispatched.complete(Unit)
            Result.success("pid")
        }
        val session = QuickPaySession()
        sut.attach(session)
        backgroundScope.launch {
            sut.payNow(session, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
        }
        dispatched.await()
        clock.instant = Instant.parse("2026-08-16T12:00:00Z")
        paymentRows = listOf(succeededRow(hash))
        sut.reconcileAgainstLdk()
        assertNotNull(sut.reserveBound("other", 200u).getOrThrow())
        val hashes = cacheStore.data.first().quickPayLedger!!.records.map { it.invoicePaymentHash }
        assertTrue(hash in hashes)
        val outcome = sut.signalCompletion(paymentId = "pid", paymentHash = hash, success = true)
        assertEquals(QuickPayCompletionKind.SETTLED_SUCCESS, outcome.kind)
        assertTrue(outcome.wasQuickPay)
        assertFalse(hash in cacheStore.data.first().quickPayLedger!!.records.map { it.invoicePaymentHash })
    }

    @Test
    fun `day-old unresolved records prune on a later reserve`() = test {
        assertNotNull(sut.reserveBound("old", 1000u).getOrThrow())
        clock.instant = Instant.parse("2026-08-16T12:00:00Z")
        assertNotNull(sut.reserveBound("new", 200u).getOrThrow())

        val hashes = cacheStore.data.first().quickPayLedger!!.records.map { it.invoicePaymentHash }
        assertFalse("old" in hashes)
        assertTrue("new" in hashes)
    }

    @Test
    fun `classifies wrapped and unwrapped ldk errors`() {
        assertEquals(
            QuickPayDispatchClass.PRE_DISPATCH_REJECTION,
            classifyDispatchError(NodeException.InvalidInvoice("bad")),
        )
        assertEquals(
            QuickPayDispatchClass.PRE_DISPATCH_REJECTION,
            classifyDispatchError(LdkError(NodeException.InvalidInvoice("bad"))),
        )
        assertEquals(
            QuickPayDispatchClass.DUPLICATE_PAYMENT,
            classifyDispatchError(NodeException.DuplicatePayment("dup")),
        )
        assertEquals(
            QuickPayDispatchClass.DUPLICATE_PAYMENT,
            classifyDispatchError(LdkError(NodeException.DuplicatePayment("dup"))),
        )
        assertEquals(
            QuickPayDispatchClass.AMBIGUOUS,
            classifyDispatchError(NodeException.PersistenceFailed("io")),
        )
        assertEquals(
            QuickPayDispatchClass.AMBIGUOUS,
            classifyDispatchError(LdkError(NodeException.PaymentSendingFailed("send"))),
        )
    }

    @Test
    fun `invalid invoice pay does not dispatch`() = test {
        val session = QuickPaySession()
        sut.attach(session)
        sut.pay(session, QuickPayPayRequest.Bolt11(bolt11 = "lnbcrt1test", amountSats = 500u))

        verify(lightningRepo, never()).payInvoice(any(), anyOrNull(), any())
    }

    @Test
    fun `duplicate payment with pending ldk does not refund`() = test {
        val (bolt11, hash) = testInvoice()
        stubPayInvoiceFailure(NodeException.DuplicatePayment("dup"))
        paymentRows = listOf(pendingRow(hash))
        val session = QuickPaySession()

        sut.attach(session).test {
            sut.payNow(session, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
            val pending = assertIs<QuickPaySessionEvent.Pending>(awaitItem())
            assertEquals(hash, pending.paymentHash)
        }
        assertEquals(250L, spentCents())
        assertEquals(1, cacheStore.data.first().quickPayLedger!!.records.size)
    }

    @Test
    fun `duplicate payment with succeeded ldk refunds a fresh reserve and emits success`() = test {
        val (bolt11, hash) = testInvoice()
        stubPayInvoiceFailure(NodeException.DuplicatePayment("dup"))
        paymentRows = listOf(succeededRow(hash))
        val session = QuickPaySession()

        sut.attach(session).test {
            sut.payNow(session, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
            val success = assertIs<QuickPaySessionEvent.Success>(awaitItem())
            assertEquals(hash, success.paymentHash)
        }
        assertEquals(0L, spentCents())
        assertTrue(cacheStore.data.first().quickPayLedger!!.records.isEmpty())
    }

    @Test
    fun `re-pay of a settled hash does not double-count`() = test {
        val (bolt11, hash) = testInvoice()
        assertNotNull(sut.reserveBound(hash, 500u).getOrThrow())
        sut.signalCompletion(paymentId = null, paymentHash = hash, success = true)
        assertEquals(250L, spentCents())
        stubPayInvoiceFailure(NodeException.DuplicatePayment("dup"))
        paymentRows = listOf(succeededRow(hash))
        val session = QuickPaySession()

        sut.attach(session).test {
            sut.payNow(session, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
            assertIs<QuickPaySessionEvent.Success>(awaitItem())
        }
        assertEquals(250L, spentCents())
        assertTrue(cacheStore.data.first().quickPayLedger!!.records.isEmpty())
    }

    @Test
    fun `ambiguous pending emits pending and keeps spend`() = test {
        val (bolt11, hash) = testInvoice()
        stubPayInvoiceFailure(NodeException.PaymentSendingFailed("send"))
        paymentRows = listOf(pendingRow(hash))
        val session = QuickPaySession()

        sut.attach(session).test {
            sut.payNow(session, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
            val pending = assertIs<QuickPaySessionEvent.Pending>(awaitItem())
            assertEquals(hash, pending.paymentHash)
        }
        assertEquals(250L, spentCents())
        verify(pendingPaymentRepo).track(hash)
    }

    @Test
    fun `rescan of a pending hash replays pending to a new session`() = test {
        val (bolt11, hash) = testInvoice()
        stubPayInvoiceFailure(NodeException.DuplicatePayment("dup"))
        paymentRows = listOf(pendingRow(hash))
        val first = QuickPaySession()
        val second = QuickPaySession()

        sut.attach(first).test {
            sut.payNow(first, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
            assertIs<QuickPaySessionEvent.Pending>(awaitItem())
        }
        sut.attach(second).test {
            sut.payNow(second, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
            assertIs<QuickPaySessionEvent.Pending>(awaitItem())
        }
        verify(lightningRepo, times(1)).payInvoice(any(), anyOrNull(), any())
        assertEquals(250L, spentCents())
        assertEquals(1, cacheStore.data.first().quickPayLedger!!.records.size)
    }

    @Test
    fun `rescan pending then success settles once`() = test {
        val (bolt11, hash) = testInvoice()
        stubPayInvoiceFailure(NodeException.DuplicatePayment("dup"))
        paymentRows = listOf(pendingRow(hash))
        val first = QuickPaySession()
        val second = QuickPaySession()

        sut.attach(first).test {
            sut.payNow(first, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
            assertIs<QuickPaySessionEvent.Pending>(awaitItem())
            sut.attach(second).test {
                sut.payNow(second, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
                assertIs<QuickPaySessionEvent.Pending>(awaitItem())
                val outcome = sut.signalCompletion(paymentId = "pid", paymentHash = hash, success = true)
                assertTrue(outcome.wasQuickPay)
                expectNoEvents()
            }
            expectNoEvents()
        }
        assertEquals(250L, spentCents())
        assertTrue(cacheStore.data.first().quickPayLedger!!.records.isEmpty())
    }

    @Test
    fun `zombie rescan replays pending after detach timeout`() = test {
        val (bolt11, hash) = testInvoice()
        val dispatched = CompletableDeferred<Unit>()
        whenever { lightningRepo.payInvoice(any(), anyOrNull(), any()) }.doSuspendableAnswer { invocation ->
            val onBeforeSend = invocation.getArgument<suspend () -> Boolean>(2)
            if (!onBeforeSend()) return@doSuspendableAnswer Result.failure(PaymentAbortedBeforeSend())
            dispatched.complete(Unit)
            Result.success("pid")
        }
        val first = QuickPaySession()
        sut.attach(first)
        backgroundScope.launch {
            sut.payNow(first, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
        }
        dispatched.await()
        sut.detach(first)
        advanceTimeBy(LightningRepo.SEND_LN_TIMEOUT)
        advanceUntilIdle()
        val second = QuickPaySession()
        sut.attach(second).test {
            sut.payNow(second, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
            assertIs<QuickPaySessionEvent.Pending>(awaitItem())
        }
        verify(lightningRepo, times(1)).payInvoice(any(), anyOrNull(), any())
        verify(pendingPaymentRepo).track(hash)
        assertEquals(250L, spentCents())
        assertEquals(1, cacheStore.data.first().quickPayLedger!!.records.size)
    }

    @Test
    fun `detachAll does not sweep a session attached after snapshot`() = test {
        val first = QuickPaySession()
        sut.attach(first)
        sut.detachAll()
        stubPayInvoiceFailure(NodeException.InvalidInvoice("bad"))
        val second = QuickPaySession()
        sut.attach(second).test {
            sut.payNow(second, QuickPayPayRequest.Bolt11(bolt11 = testInvoice().first, amountSats = 500u))
            assertIs<QuickPaySessionEvent.Error>(awaitItem())
        }
    }

    @Test
    fun `hasOpen is true for a live op or recovered row`() = test {
        val (bolt11, hash) = testInvoice()
        assertFalse(sut.hasOpen(hash))
        stubPayInvoiceFailure(NodeException.DuplicatePayment("dup"))
        paymentRows = listOf(pendingRow(hash))
        val session = QuickPaySession()
        sut.attach(session)
        sut.payNow(session, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
        assertTrue(sut.hasOpen(hash))
        val recovered = "recovered-hash"
        assertNotNull(sut.reserveBound(recovered, 500u).getOrThrow())
        assertTrue(repo().hasOpen(recovered))
        sut.signalCompletion(paymentId = null, paymentHash = hash, success = true)
        assertFalse(sut.hasOpen(hash))
    }

    @Test
    fun `second pay of an in-flight hash does not fall back to confirm`() = test {
        val (bolt11, _) = testInvoice()
        val started = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Result<String>>()
        whenever { lightningRepo.payInvoice(any(), anyOrNull(), any()) }.doSuspendableAnswer { invocation ->
            val onBeforeSend = invocation.getArgument<suspend () -> Boolean>(2)
            if (!onBeforeSend()) return@doSuspendableAnswer Result.failure(PaymentAbortedBeforeSend())
            started.complete(Unit)
            hold.await()
        }
        val session = QuickPaySession()
        sut.attach(session)
        backgroundScope.launch {
            sut.payNow(session, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
        }
        started.await()
        sut.payNow(session, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))

        verify(lightningRepo, times(1)).payInvoice(any(), anyOrNull(), any())
        hold.complete(Result.success("pid"))
    }

    @Test
    fun `recovered submitting hash emits pending and does not pay`() = test {
        val (bolt11, hash) = testInvoice()
        assertNotNull(sut.reserveBound(hash, 500u).getOrThrow())
        val reloaded = repo()
        val session = QuickPaySession()

        reloaded.attach(session).test {
            reloaded.payNow(session, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
            val pending = assertIs<QuickPaySessionEvent.Pending>(awaitItem())
            assertEquals(hash, pending.paymentHash)
        }
        assertEquals(250L, spentCents())
        verify(lightningRepo, never()).payInvoice(any(), anyOrNull(), any())
        verify(pendingPaymentRepo).track(hash)
    }

    @Test
    fun `recovered hash that ldk already succeeded emits success`() = test {
        val (bolt11, hash) = testInvoice()
        assertNotNull(sut.reserveBound(hash, 500u).getOrThrow())
        val reloaded = repo()
        paymentRows = listOf(succeededRow(hash))
        val session = QuickPaySession()

        reloaded.attach(session).test {
            reloaded.payNow(session, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
            assertIs<QuickPaySessionEvent.Success>(awaitItem())
        }
        assertEquals(250L, spentCents())
        assertTrue(cacheStore.data.first().quickPayLedger!!.records.isEmpty())
        verify(lightningRepo, never()).payInvoice(any(), anyOrNull(), any())
    }

    @Test
    fun `reserve persists before dispatch`() = test {
        val (bolt11, _) = testInvoice()
        whenever { lightningRepo.payInvoice(any(), anyOrNull(), any()) }.doSuspendableAnswer { invocation ->
            val onBeforeSend = invocation.getArgument<suspend () -> Boolean>(2)
            assertEquals(250L, spentCents())
            assertEquals(1, cacheStore.data.first().quickPayLedger!!.records.size)
            if (!onBeforeSend()) return@doSuspendableAnswer Result.failure(PaymentAbortedBeforeSend())
            Result.failure(LdkError(NodeException.InvalidInvoice("done")))
        }
        val session = QuickPaySession()
        sut.attach(session)
        sut.payNow(session, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
    }

    @Test
    fun `detach before dispatch aborts and releases`() = test {
        val (bolt11, _) = testInvoice()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val proceeded = CompletableDeferred<Boolean>()
        whenever { lightningRepo.payInvoice(any(), anyOrNull(), any()) }.doSuspendableAnswer { invocation ->
            val onBeforeSend = invocation.getArgument<suspend () -> Boolean>(2)
            entered.complete(Unit)
            withContext(NonCancellable) {
                gate.await()
                val ok = onBeforeSend()
                proceeded.complete(ok)
                if (!ok) Result.failure(PaymentAbortedBeforeSend()) else Result.success("pid")
            }
        }
        val session = QuickPaySession()
        sut.attach(session).test {
            backgroundScope.launch {
                sut.payNow(session, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
            }
            entered.await()
            sut.detach(session)
            gate.complete(Unit)
            assertEquals(false, proceeded.await())
            expectNoEvents()
        }
        assertEquals(0L, spentCents())
        assertTrue(cacheStore.data.first().quickPayLedger!!.records.isEmpty())
        verify(lightningRepo, times(1)).payInvoice(any(), anyOrNull(), any())
    }

    @Test
    fun `pre-dispatch rejection refunds after dispatch`() = test {
        val (bolt11, _) = testInvoice()
        stubPayInvoiceFailure(NodeException.InvalidInvoice("bad"))
        val session = QuickPaySession()
        sut.attach(session).test {
            sut.payNow(session, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
            assertIs<QuickPaySessionEvent.Error>(awaitItem())
        }
        assertEquals(0L, spentCents())
        assertTrue(cacheStore.data.first().quickPayLedger!!.records.isEmpty())
    }

    @Test
    fun `null payment rows mutate nothing on duplicate`() = test {
        val (bolt11, _) = testInvoice()
        stubPayInvoiceFailure(NodeException.DuplicatePayment("dup"))
        val session = QuickPaySession()
        sut.attach(session).test {
            sut.payNow(session, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
            assertIs<QuickPaySessionEvent.Pending>(awaitItem())
        }
        assertEquals(250L, spentCents())
        assertEquals(1, cacheStore.data.first().quickPayLedger!!.records.size)
    }

    @Test
    fun `reconcile during live dispatched op does not steal completion`() = test {
        val (bolt11, hash) = testInvoice()
        val dispatched = CompletableDeferred<Unit>()
        whenever { lightningRepo.payInvoice(any(), anyOrNull(), any()) }.doSuspendableAnswer { invocation ->
            val onBeforeSend = invocation.getArgument<suspend () -> Boolean>(2)
            if (!onBeforeSend()) return@doSuspendableAnswer Result.failure(PaymentAbortedBeforeSend())
            dispatched.complete(Unit)
            Result.success("pid")
        }
        val session = QuickPaySession()

        sut.attach(session).test {
            backgroundScope.launch {
                sut.payNow(session, QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = 500u))
            }
            dispatched.await()
            paymentRows = listOf(succeededRow(hash))
            sut.reconcileAgainstLdk()
            assertEquals(250L, spentCents())
            assertEquals(1, cacheStore.data.first().quickPayLedger!!.records.size)
            val outcome = sut.signalCompletion(paymentId = "pid", paymentHash = hash, success = true)
            assertEquals(QuickPayCompletionKind.SETTLED_SUCCESS, outcome.kind)
            assertTrue(outcome.wasQuickPay)
            assertIs<QuickPaySessionEvent.Success>(awaitItem())
        }
        assertEquals(250L, spentCents())
        assertTrue(cacheStore.data.first().quickPayLedger!!.records.isEmpty())
    }

    private suspend fun spentCents(): Long =
        cacheStore.data.first().quickPayLedger?.spentCents ?: 0L

    private suspend fun markSubmitted(invoiceHash: String, paymentId: String) {
        cacheStore.update { data ->
            val ledger = requireNotNull(data.quickPayLedger)
            val index = ledger.records.indexOfFirst { it.invoicePaymentHash == invoiceHash }
            val record = ledger.records[index].copy(
                paymentId = paymentId,
                phase = QuickPayRecordPhase.SUBMITTED,
            )
            val next = ledger.copy(records = ledger.records.toMutableList().also { it[index] = record })
            data.copy(quickPayLedger = next)
        }
    }

    private suspend fun stubPayInvoiceFailure(error: NodeException) {
        whenever { lightningRepo.payInvoice(any(), anyOrNull(), any()) }.doSuspendableAnswer { invocation ->
            val onBeforeSend = invocation.getArgument<suspend () -> Boolean>(2)
            if (!onBeforeSend()) return@doSuspendableAnswer Result.failure(PaymentAbortedBeforeSend())
            Result.failure(LdkError(error))
        }
    }

    private fun pendingRow(hash: String) = QuickPayReconcileRow(
        paymentId = "pid",
        invoicePaymentHash = hash,
        isOutboundBolt11 = true,
        status = QuickPayReconcileRow.Status.PENDING,
    )

    private fun succeededRow(hash: String) = QuickPayReconcileRow(
        paymentId = "pid",
        invoicePaymentHash = hash,
        isOutboundBolt11 = true,
        status = QuickPayReconcileRow.Status.SUCCEEDED,
    )

    private fun testInvoice(): Pair<String, String> = TEST_BOLT11 to TEST_HASH

    private fun repo(): QuickPayRepo {
        return QuickPayRepo(
            cacheStore = cacheStore,
            settingsStore = settingsStore,
            currencyRepo = currencyRepo,
            lightningRepo = lightningRepo,
            pendingPaymentRepo = pendingPaymentRepo,
            invoiceParser = QuickPayInvoiceParser { bolt11 ->
                bolt11.takeIf { it == TEST_BOLT11 }?.let { TEST_HASH }
            },
            paymentLookup = QuickPayPaymentLookup {
                paymentRows ?: lightningRepo.listPaymentsOrNull()?.map { QuickPayReconcileRow(it) }
            },
            ioDispatcher = testDispatcher,
            clock = clock,
        )
    }

    private fun stubZeroCentConversion(dustSats: Long) {
        whenever(currencyRepo.convertSatsToFiat(any(), anyOrNull())).thenAnswer { invocation ->
            val sats = invocation.getArgument<Long>(0)
            val usd = if (sats == dustSats) 0.004 else 5.0 * sats.toDouble() / 1000.0
            ConvertedAmount(
                value = BigDecimal.valueOf(usd),
                formatted = usd.toString(),
                symbol = "$",
                currency = "USD",
                flag = "",
                sats = sats,
                locale = Locale.US,
            )
        }
    }
}

private class MutableClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant
}
