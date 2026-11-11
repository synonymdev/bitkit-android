package to.bitkit.repositories

import breez_sdk_spark.BitcoinAddressDetails
import breez_sdk_spark.Bolt11InvoiceDetails
import breez_sdk_spark.ClaimDeferredReason
import breez_sdk_spark.ClaimDepositOutcome
import breez_sdk_spark.ClaimDepositQuote
import breez_sdk_spark.DepositInfo
import breez_sdk_spark.FeePolicy
import breez_sdk_spark.FetchClaimDepositQuoteResponse
import breez_sdk_spark.GetInfoResponse
import breez_sdk_spark.ImportUnilateralExitStateResponse
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PaymentMethod
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PaymentType
import breez_sdk_spark.PrepareSendPaymentResponse
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.SdkException
import breez_sdk_spark.SendOnchainFeeQuote
import breez_sdk_spark.SendOnchainSpeedFeeQuote
import breez_sdk_spark.SendPaymentMethod
import breez_sdk_spark.SparkHtlcDetails
import breez_sdk_spark.SparkHtlcStatus
import breez_sdk_spark.SyncError
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaykitEndpoint
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.ServiceRouteLeg
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.lightningdevkit.ldknode.Event
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.MAX_SPARK_SEND_HASHES
import to.bitkit.env.Env
import to.bitkit.ext.create
import to.bitkit.ext.scopedActivityId
import to.bitkit.models.SparkExitBackupV1
import to.bitkit.models.WalletScope
import to.bitkit.services.SparkError
import to.bitkit.services.SparkService
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.InMemoryDataStore
import to.bitkit.utils.AppError
import to.bitkit.utils.ServiceError
import java.io.File
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import com.synonym.bitkitcore.PaymentType as ActivityPaymentType

@OptIn(ExperimentalCoroutinesApi::class)
class SparkRepoTest : BaseUnitTest() {
    private companion object {
        const val ADDRESS = "bcrt1qsavings"
    }

    private val sparkService = mock<SparkService>()
    private val activityRepo = mock<ActivityRepo>()
    private val sparkEvents = MutableSharedFlow<SdkEvent>()
    private val cacheData = MutableStateFlow(AppCacheData())
    private val cacheStore = mock<CacheStore> { on { data } doReturn cacheData }
    private val contactResolver = mock<PrivatePaykitContactResolver>()
    private val pendingPaymentRepo = PendingPaymentRepo()
    private var now = Instant.fromEpochSeconds(1_800_000_000)
    private val clock = object : Clock {
        override fun now() = this@SparkRepoTest.now
    }

    private fun createSut(cacheStore: CacheStore = this.cacheStore) = SparkRepo(
        bgDispatcher = testDispatcher,
        sparkService = sparkService,
        activityRepo = { activityRepo },
        cacheStore = cacheStore,
        contactResolver = { contactResolver },
        pendingPaymentRepo = pendingPaymentRepo,
        clock = clock,
    )

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        Env.initAppStoragePath(tempFolder.root.absolutePath)
        whenever { sparkService.getInfo() }.thenReturn(info(0uL))
        whenever { sparkService.listUnclaimedDeposits() }.thenReturn(emptyList())
        whenever { sparkService.listPayments() }.thenReturn(emptyList())
        whenever(sparkService.events).thenReturn(sparkEvents)
        whenever { sparkService.connect(any()) }.thenReturn(true)
        whenever { activityRepo.getActivity(any(), any()) }.thenReturn(Result.success(null))
        whenever { activityRepo.upsertActivity(any()) }.thenReturn(Result.success(Unit))
        whenever(sparkService.isSupported).thenReturn(true)
        whenever(sparkService.networkName).thenReturn("regtest")
    }

    @Test
    fun `imports a restored exit backup once Spark connects`() = test {
        whenever { sparkService.importExitState(any()) }.thenReturn(ImportUnilateralExitStateResponse(2u, 0u, 0u, 0u))
        val sut = createSut()

        sut.restoreExitBackup(SparkExitBackupV1(createdAt = 1, network = "regtest", exitState = "state"), 0)
            .getOrThrow()
        verify(sparkService, never()).importExitState(any())

        whenever(sparkService.isConnected).thenReturn(true)
        sut.start(0).getOrThrow()

        verify(sparkService).importExitState("state")
        assertFalse(File(Env.sparkExitRestorePath(0)).exists())
    }

    @Test
    fun `a start while already connected neither redoes the exit backup nor rewrites every activity`() = test {
        whenever(sparkService.isConnected).thenReturn(false, true)
        whenever(sparkService.connect(0)).thenReturn(true, false)
        whenever { sparkService.listPayments() }.thenReturn(listOf(sentPayment(fees = 2)))
        val sut = createSut()
        sut.start(0).getOrThrow()
        sut.start(0).getOrThrow()
        runCurrent()

        assertEquals(1L, sut.exitStateGeneration.value, "exit data backup signals after two starts")
        verify(activityRepo, times(1)).upsertActivity(any())
    }

    @Test
    fun `an invoice spark is still paying is reported pending`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.listPayments() }
            .thenReturn(listOf(sentPayment(fees = 2).copy(status = PaymentStatus.PENDING)))

        val sut = createSut()
        sut.start(0).getOrThrow()

        val earlier = sut.earlierLightningSend("lnbc1280n1send", null)

        assertEquals(EarlierSend.Pending("hash"), earlier)
    }

    @Test
    fun `an unreadable history holds the node off an invoice spark started sending`() = test {
        cacheData.value = AppCacheData(sparkSendHashes = listOf("hash"))
        whenever(sparkService.isConnected).thenReturn(false)
        val sut = createSut()

        assertEquals(EarlierSend.Pending("hash"), sut.earlierLightningSend("lnbc1280n1send", "HASH"))

        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.listPayments() }.thenAnswer { throw IllegalStateException("offline") }
        assertEquals(EarlierSend.Pending("hash"), sut.earlierLightningSend("lnbc1280n1send", "hash"))
    }

    @Test
    fun `an empty history spark has not synced yet holds the node off an invoice spark started sending`() = test {
        cacheData.value = AppCacheData(sparkSendHashes = listOf("hash"))
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.listPayments() }.thenReturn(emptyList())

        assertEquals(EarlierSend.Pending("hash"), createSut().earlierLightningSend("lnbc1280n1send", "hash"))
    }

    @Test
    fun `an unreadable history lets the node pay an invoice spark never sent`() = test {
        cacheData.value = AppCacheData(sparkSendHashes = listOf("other"))
        whenever(sparkService.isConnected).thenReturn(false)

        assertNull(createSut().earlierLightningSend("lnbc1280n1send", "hash"))
    }

    @Test
    fun `a spark retry recorded while an older list is read holds the node off the invoice`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val failed = sentPayment(fees = 0).copy(status = PaymentStatus.FAILED)
        whenever { sparkService.listPayments() }.thenReturn(listOf(failed))
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.thenReturn(sentPayment(fees = 0).copy(status = PaymentStatus.PENDING))
        val sut = createSut()
        sut.start(0).getOrThrow()
        var isRetried = false
        // Spark retries the invoice the node is about to pay, and its send returns, while this list is read.
        whenever { sparkService.listPayments() }.doSuspendableAnswer {
            if (!isRetried) {
                isRetried = true
                sut.payInvoice("lnbc1280n1send", 128uL)
            }
            listOf(failed)
        }

        assertEquals(EarlierSend.Pending("hash"), sut.earlierLightningSend("lnbc1280n1send", "hash"))
    }

    @Test
    fun `a readable history without a live attempt lets the node pay a recorded invoice`() = test {
        cacheData.value = AppCacheData(sparkSendHashes = listOf("hash"))
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.listPayments() }
            .thenReturn(listOf(sentPayment(fees = 0).copy(status = PaymentStatus.FAILED)))
        val sut = createSut()
        sut.start(0).getOrThrow()

        assertNull(sut.earlierLightningSend("lnbc1280n1send", "hash"))
    }

    @Test
    fun `a spark send is recorded before it goes out`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.listPayments() }.thenReturn(emptyList())
        whenever { sparkService.send(any()) }.thenReturn(sentPayment(fees = 0))

        createSut().payInvoice("lnbc1280n1send", 128uL).getOrThrow()

        inOrder(cacheStore, sparkService) {
            verifyBlocking(cacheStore) { addSparkSendHash(eq("hash"), anyOrNull()) }
            verifyBlocking(sparkService) { send(any()) }
        }
    }

    @Test
    fun `a send spark failed is not held from the node while spark is disconnected`() = test {
        cacheData.value = AppCacheData(sparkSendHashes = listOf("hash"))
        whenever { cacheStore.removeSparkSendHash(any()) }.thenAnswer { call ->
            cacheData.update { it.copy(sparkSendHashes = it.sparkSendHashes - call.getArgument<String>(0)) }
        }
        whenever(sparkService.isConnected).thenReturn(true)
        val sut = createSut()
        sut.start(0).getOrThrow()

        sparkEvents.emit(SdkEvent.PaymentFailed(sentPayment(fees = 0).copy(status = PaymentStatus.FAILED)))
        whenever(sparkService.isConnected).thenReturn(false)

        assertNull(sut.earlierLightningSend("lnbc1280n1send", "hash"))
    }

    @Test
    fun `a send that failed while the app was closed is released when spark syncs on start`() = test {
        cacheData.value = AppCacheData(sparkSendHashes = listOf("hash"))
        whenever { cacheStore.removeSparkSendHash(any()) }.thenAnswer { call ->
            cacheData.update { it.copy(sparkSendHashes = it.sparkSendHashes - call.getArgument<String>(0)) }
        }
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.listPayments() }
            .thenReturn(listOf(sentPayment(fees = 0).copy(status = PaymentStatus.FAILED)))
        val sut = createSut()

        sut.start(0).getOrThrow()
        whenever(sparkService.isConnected).thenReturn(false)

        assertNull(sut.earlierLightningSend("lnbc1280n1send", "hash"))
    }

    @Test
    fun `a spark send with no row holds the node after a restart until its invoice expires`() = test {
        cacheData.value = AppCacheData(
            sparkSendHashes = listOf("hash", "expired"),
            sparkSendExpiries = mapOf("hash" to now.epochSeconds + 3_600, "expired" to now.epochSeconds - 1),
        )
        whenever(sparkService.isConnected).thenReturn(true)
        val sut = createSut()

        sut.start(0).getOrThrow()

        assertEquals(
            EarlierSend.Pending("hash"),
            sut.earlierLightningSend("lnbc1280n1send", "hash"),
            "The node may pay an invoice whose Spark send a later sync can still resume",
        )
        assertEquals(PaymentStatus.PENDING, sut.lightningSendStatuses()?.get("hash"))
        assertEquals(setOf("hash"), sut.attemptedLightningPaymentHashes())
        assertNull(sut.earlierLightningSend("lnbc1280n1expired", "expired"))
    }

    @Test
    fun `a paid send whose invoice is unexpired still holds the node behind the cap while spark cant list`() = test {
        val store = CacheStore(InMemoryDataStore(AppCacheData()), clock)
        store.addSparkSendHash("hash", expiresAt = now.epochSeconds + 3_600)
        val newer = (1..MAX_SPARK_SEND_HASHES).map { sentPayment(fees = 2).copy(id = "send$it").withHash("newer$it") }
        (1..MAX_SPARK_SEND_HASHES).forEach { store.addSparkSendHash("newer$it", expiresAt = now.epochSeconds - 1) }
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.listPayments() }.thenReturn(listOf(sentPayment(fees = 2)) + newer)
        val sut = createSut(store)
        sut.start(0).getOrThrow()
        whenever { sparkService.listPayments() }.thenThrow(IllegalStateException("list failed"))

        val earlier = sut.earlierLightningSend("lnbc1280n1other", "hash")

        assertEquals(EarlierSend.Pending("hash"), earlier, "The node may pay an unexpired invoice Spark already paid")
    }

    @Test
    fun `spark's paid and failed sends are trimmed past the cap when spark syncs on start`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.listPayments() }.thenReturn(
            listOf(
                sentPayment(fees = 0),
                sentPayment(fees = 0).copy(id = "failed", status = PaymentStatus.FAILED).withHash("FAILED"),
                sentPayment(fees = 0).copy(id = "pending", status = PaymentStatus.PENDING).withHash("PENDING"),
                sentPayment(fees = 0).copy(id = "retry", status = PaymentStatus.FAILED).withHash("PENDING"),
            )
        )
        val sut = createSut()

        sut.start(0).getOrThrow()

        verifyBlocking(cacheStore) {
            trimSparkSendHashes(
                paid = setOf("hash"),
                failed = setOf("failed"),
                listed = setOf("hash", "failed", "pending"),
                live = setOf("pending"),
            )
        }
    }

    @Test
    fun `a retry that starts and returns while the activity sync runs keeps its hash`() = test {
        val store = CacheStore(InMemoryDataStore(AppCacheData(sparkSendHashes = listOf("hash"))), clock)
        whenever(sparkService.isConnected).thenReturn(true)
        val failed = sentPayment(fees = 0).copy(status = PaymentStatus.FAILED)
        val retry = sentPayment(fees = 0).copy(id = "retry", status = PaymentStatus.PENDING)
        whenever { sparkService.listPayments() }.thenReturn(listOf(failed), listOf(failed, retry))
        val recording = CompletableDeferred<Unit>()
        whenever { activityRepo.getActivity(any(), any()) }.doSuspendableAnswer {
            recording.await()
            Result.success(null)
        }
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.thenReturn(retry)
        val sut = createSut(store)
        val starting = async { sut.start(0) }
        runCurrent()
        // The listing read before the activity loop shows the invoice failed; its retry starts and returns meanwhile.
        val paying = async { sut.payInvoice("lnbc1280n1send", 128uL) }
        runCurrent()
        recording.complete(Unit)
        starting.await().getOrThrow()
        paying.await()

        assertTrue("hash" in store.data.first().sparkSendHashes, "A stale listing dropped a live retry's hash")
    }

    @Test
    fun `a retry in flight when the activity sync reads spark's list keeps its hash after it ends`() = test {
        val store = CacheStore(InMemoryDataStore(AppCacheData(sparkSendHashes = listOf("hash"))), clock)
        whenever(sparkService.isConnected).thenReturn(true)
        val failed = sentPayment(fees = 0).copy(status = PaymentStatus.FAILED)
        val retry = sentPayment(fees = 0).copy(id = "retry", status = PaymentStatus.PENDING)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        val sending = CompletableDeferred<Unit>()
        whenever { sparkService.send(any()) }.doSuspendableAnswer {
            sending.await()
            retry
        }
        var paying: Deferred<Result<String>>? = null
        var lists = 0
        // The retry recorded before this list ends while the list, which still shows it failed, is read.
        whenever { sparkService.listPayments() }.doSuspendableAnswer {
            // The refresh reads the list first, then the activity sync.
            val list = paying?.let { ++lists } ?: 0
            if (list == 2) {
                sending.complete(Unit)
                paying?.await()
            }
            if (list <= 2) listOf(failed) else listOf(failed, retry)
        }
        val sut = createSut(store)
        paying = backgroundScope.async { sut.payInvoice("lnbc1280n1send", 128uL) }
        runCurrent()

        sut.start(0).getOrThrow()

        assertTrue("hash" in store.data.first().sparkSendHashes, "A stale listing dropped a retry that ended")
    }

    @Test
    fun `a retry in flight when the release reads spark's list again keeps its hash after it ends`() = test {
        cacheData.value = AppCacheData(sparkSendHashes = listOf("hash"))
        whenever { cacheStore.removeSparkSendHash(any()) }.thenAnswer { call ->
            cacheData.update { it.copy(sparkSendHashes = it.sparkSendHashes - call.getArgument<String>(0)) }
        }
        whenever(sparkService.isConnected).thenReturn(true)
        val failed = sentPayment(fees = 0).copy(status = PaymentStatus.FAILED)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        val sending = CompletableDeferred<Unit>()
        whenever { sparkService.send(any()) }.doSuspendableAnswer {
            sending.await()
            sentPayment(fees = 0).copy(status = PaymentStatus.PENDING)
        }
        var paying: Deferred<Result<String>>? = null
        var lists = 0
        // The retry ends while the release's second read, which still shows it failed, is read.
        whenever { sparkService.listPayments() }.doSuspendableAnswer {
            // The refresh and the activity sync read the list first, then the release reads it again.
            if (paying != null && ++lists == 3) {
                sending.complete(Unit)
                paying?.await()
            }
            listOf(failed)
        }
        val sut = createSut()
        paying = backgroundScope.async { sut.payInvoice("lnbc1280n1send", 128uL) }
        runCurrent()

        sut.start(0).getOrThrow()

        assertTrue("hash" in cacheData.value.sparkSendHashes, "The release dropped a retry that ended while it read")
    }

    @Test
    fun `a claimed deposit hides only its own output of the transaction`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.listUnclaimedDeposits() }.thenReturn(
            listOf(0u, 1u).map { DepositInfo("txid", it, 50_000uL, false, null, null, null, null, null, null) }
        )
        whenever { sparkService.listPayments() }.thenReturn(listOf(depositPayment()))
        val sut = createSut()

        sut.refreshState().getOrThrow()

        assertEquals(50_000uL, sut.state.value.pendingDepositSats, "another output of the claimed tx was hidden")
    }

    @Test
    fun `a failed send does not release a hash its retry still holds pending`() = test {
        cacheData.value = AppCacheData(sparkSendHashes = listOf("hash"))
        whenever { cacheStore.removeSparkSendHash(any()) }.thenAnswer { call ->
            cacheData.update { it.copy(sparkSendHashes = it.sparkSendHashes - call.getArgument<String>(0)) }
        }
        whenever(sparkService.isConnected).thenReturn(true)
        val failed = sentPayment(fees = 0).copy(id = "first", status = PaymentStatus.FAILED)
        val retry = sentPayment(fees = 0).copy(id = "retry", status = PaymentStatus.PENDING)
        whenever { sparkService.listPayments() }.thenReturn(listOf(failed, retry))
        val sut = createSut()

        sut.start(0).getOrThrow()
        sparkEvents.emit(SdkEvent.PaymentFailed(failed))
        whenever(sparkService.isConnected).thenReturn(false)

        assertEquals(EarlierSend.Pending("hash"), sut.earlierLightningSend("lnbc1280n1send", "hash"))
    }

    @Test
    fun `a retry spark is still sending keeps its hash held through the activity sync`() = test {
        cacheData.value = AppCacheData(sparkSendHashes = listOf("hash"))
        whenever { cacheStore.removeSparkSendHash(any()) }.thenAnswer { call ->
            cacheData.update { it.copy(sparkSendHashes = it.sparkSendHashes - call.getArgument<String>(0)) }
        }
        whenever(sparkService.isConnected).thenReturn(true)
        val failed = sentPayment(fees = 0).copy(status = PaymentStatus.FAILED)
        whenever { sparkService.listPayments() }.thenReturn(listOf(failed))
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        val sending = CompletableDeferred<Unit>()
        whenever { sparkService.send(any()) }.doSuspendableAnswer {
            sending.await()
            sentPayment(fees = 0).copy(status = PaymentStatus.PENDING)
        }
        val sut = createSut()
        backgroundScope.launch { sut.payInvoice("lnbc1280n1send", 128uL) }
        runCurrent()

        sut.start(0).getOrThrow()

        assertTrue("hash" in cacheData.value.sparkSendHashes)
        sending.complete(Unit)
    }

    @Test
    fun `an uncertain send spark never recorded is no longer held from the node`() = test {
        cacheData.value = AppCacheData(sparkSendHashes = listOf("hash"))
        whenever { cacheStore.removeSparkSendHash(any()) }.thenAnswer { call ->
            cacheData.update { it.copy(sparkSendHashes = it.sparkSendHashes - call.getArgument<String>(0)) }
        }
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.thenAnswer { throw IllegalStateException("lost") }
        // Spark's resync reaches it after the send failed, so a send it doesn't list never went out.
        whenever { sparkService.getInfo() }.thenReturn(info(0uL).copy(lastSyncedAt = now.epochSeconds.toULong() + 1uL))
        val sut = createSut()

        assertIs<PaymentPendingException>(sut.payInvoice("lnbc1280n1send", 128uL).exceptionOrNull())
        advanceTimeBy(15_000)
        runCurrent()
        whenever(sparkService.isConnected).thenReturn(false)

        assertNull(sut.earlierLightningSend("lnbc1280n1send", "hash"))
    }

    @Test
    fun `replaying a send keeps the payment note stored on its activity`() = test {
        whenever { activityRepo.getActivity(eq("hash"), any()) }
            .thenReturn(Result.success(Activity.Lightning(storedSend(PaymentState.SUCCEEDED, message = "for coffee"))))
        whenever { sparkService.listPayments() }.thenReturn(listOf(sentPayment(fees = 2)))
        whenever(sparkService.isConnected).thenReturn(true)

        createSut().start(0).getOrThrow()

        val activity = argumentCaptor<Activity>()
        verify(activityRepo).upsertActivity(activity.capture())
        assertEquals("for coffee", (activity.firstValue as Activity.Lightning).v1.message)
    }

    @Test
    fun `replaying a failed spark attempt never downgrades a payment that succeeded`() = test {
        whenever { activityRepo.getActivity(eq("hash"), any()) }
            .thenReturn(Result.success(Activity.Lightning(storedSend(PaymentState.SUCCEEDED))))
        whenever { sparkService.listPayments() }
            .thenReturn(listOf(sentPayment(fees = 0).copy(status = PaymentStatus.FAILED)))
        whenever(sparkService.isConnected).thenReturn(true)

        createSut().start(0).getOrThrow()

        verify(activityRepo, never()).upsertActivity(any())
    }

    private fun storedSend(status: PaymentState, message: String = "") = LightningActivity.create(
        id = "hash",
        txType = ActivityPaymentType.SENT,
        status = status,
        value = 128uL,
        invoice = "lnbc1280n1send",
        timestamp = 1uL,
        message = message,
    )

    @Test
    fun `records a send's fee once a sync reports it`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val sut = createSut()
        sut.start(0).getOrThrow()

        sparkEvents.emit(SdkEvent.PaymentSucceeded(sentPayment(fees = 0)))
        whenever { sparkService.listPayments() }.thenReturn(listOf(sentPayment(fees = 2)))
        sparkEvents.emit(SdkEvent.Synced)

        val activities = argumentCaptor<Activity>()
        verify(activityRepo, times(2)).upsertActivity(activities.capture())
        assertEquals(listOf(0uL, 2uL), activities.allValues.map { (it as Activity.Lightning).v1.fee })
    }

    @Test
    fun `a start whose activity write failed syncs the activities again`() = test {
        whenever(sparkService.isConnected).thenReturn(false, true)
        whenever(sparkService.connect(0)).thenReturn(true, false)
        whenever { sparkService.listPayments() }.thenReturn(listOf(receivedPayment()))
        var saved = 0
        whenever { activityRepo.upsertActivity(any()) }
            .thenReturn(Result.failure(IllegalStateException("write failed")))
            .thenAnswer { Result.success(Unit).also { saved++ } }
        val sut = createSut()

        sut.start(0)
        sut.start(0)

        assertTrue(saved > 0, "The receive whose write failed was never saved")
    }

    @Test
    fun `a receive that failed to save is written again at the next sync`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val sut = createSut()
        sut.start(0).getOrThrow()
        whenever { activityRepo.upsertActivity(any()) }
            .thenReturn(Result.failure(IllegalStateException("write failed")), Result.success(Unit))

        sparkEvents.emit(SdkEvent.PaymentSucceeded(receivedPayment()))
        whenever { sparkService.listPayments() }.thenReturn(listOf(receivedPayment()))
        sparkEvents.emit(SdkEvent.Synced)
        sparkEvents.emit(SdkEvent.Synced)

        verify(activityRepo, times(2)).upsertActivity(any())
    }

    @Test
    fun `a failed activity read keeps its stored note contact and seen time and retries at the next sync`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val sut = createSut()
        sut.start(0).getOrThrow()
        val stored = LightningActivity.create(
            id = "hash",
            txType = ActivityPaymentType.SENT,
            status = PaymentState.PENDING,
            value = 128uL,
            invoice = "lnbc1280n1send",
            timestamp = 1uL,
            message = "note",
            contact = "contact",
            seenAt = 5uL,
        )
        whenever { activityRepo.getActivity(eq("hash"), any()) }
            .thenReturn(
                Result.failure(IllegalStateException("read failed")),
                Result.success(Activity.Lightning(stored)),
            )

        sparkEvents.emit(SdkEvent.PaymentSucceeded(sentPayment(fees = 2)))
        whenever { sparkService.listPayments() }.thenReturn(listOf(sentPayment(fees = 2)))
        sparkEvents.emit(SdkEvent.Synced)

        val activities = argumentCaptor<Activity>()
        verify(activityRepo, times(1)).upsertActivity(activities.capture())
        val written = (activities.firstValue as Activity.Lightning).v1
        assertEquals("note", written.message)
        assertEquals("contact", written.contact)
        assertEquals(5uL, written.seenAt)
    }

    @Test
    fun `an activity the user deleted is not written again`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val sut = createSut()
        sut.start(0).getOrThrow()
        whenever { activityRepo.upsertActivity(any()) }.thenReturn(Result.failure(ActivityDeletedError("hash")))

        sparkEvents.emit(SdkEvent.PaymentSucceeded(receivedPayment()))
        whenever { sparkService.listPayments() }.thenReturn(listOf(receivedPayment()))
        sparkEvents.emit(SdkEvent.Synced)

        verify(activityRepo, times(1)).upsertActivity(any())
    }

    @Test
    fun `marks a withdrawal's arrival as a transfer by the txid its send returned`() = test {
        val arrival = OnchainActivity.create(
            id = "arrival",
            txType = ActivityPaymentType.RECEIVED,
            txId = "withdrawal",
            value = 4_917uL,
            fee = 0uL,
            address = "bc1q",
            timestamp = 1uL,
        )
        whenever { activityRepo.getOnchainActivityByTxId(eq("withdrawal"), any()) }.thenReturn(arrival)
        whenever { activityRepo.updateActivity(any(), any(), any()) }.thenReturn(Result.success(Unit))
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(info(10_000uL))
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }
            .thenReturn(withdrawal(expiresAt = now.epochSeconds.toULong() + 600uL))
        val sent = sentPayment(fees = 1_470).copy(
            method = PaymentMethod.WITHDRAW,
            details = PaymentDetails.Withdraw("withdrawal", 0u, ADDRESS),
        )
        whenever { sparkService.send(any()) }.thenReturn(sent)
        val sut = createSut()

        assertFalse(sut.markWithdrawalAsTransfer("withdrawal"))
        sut.withdrawAll(ADDRESS, confirmedFeeSats = 500uL).getOrThrow()

        assertTrue(sut.markWithdrawalAsTransfer("withdrawal"))
        assertFalse(sut.markWithdrawalAsTransfer("other"))
        val updated = argumentCaptor<Activity>()
        verify(activityRepo).updateActivity(eq("arrival"), updated.capture(), any())
        assertTrue((updated.firstValue as Activity.Onchain).v1.isTransfer)
    }

    @Test
    fun `lists a withdrawal with its fee and marks its arrival in Savings as a transfer`() = test {
        val arrival = OnchainActivity.create(
            id = "arrival",
            txType = ActivityPaymentType.RECEIVED,
            txId = "withdrawal",
            value = 4_917uL,
            fee = 0uL,
            address = "bc1q",
            timestamp = 1uL,
        )
        whenever { activityRepo.getOnchainActivityByTxId(eq("withdrawal"), any()) }.thenReturn(arrival)
        whenever { activityRepo.updateActivity(any(), any(), any()) }.thenReturn(Result.success(Unit))
        val withdrawal = sentPayment(fees = 1_470).copy(
            method = PaymentMethod.WITHDRAW,
            details = PaymentDetails.Withdraw("withdrawal", 0u, ADDRESS),
        )
        whenever { sparkService.listPayments() }.thenReturn(listOf(withdrawal))
        whenever(sparkService.isConnected).thenReturn(true)

        val sut = createSut()
        sut.start(0).getOrThrow()

        val updated = argumentCaptor<Activity>()
        verify(activityRepo).updateActivity(eq("arrival"), updated.capture(), any())
        assertTrue((updated.firstValue as Activity.Onchain).v1.isTransfer)
        assertEquals(listOf(SparkWithdrawal("withdrawal", 128uL, 1_470uL)), sut.state.value.withdrawals)
    }

    @Test
    fun `quotes a withdrawal to a Paykit contact as a route leg`() = test {
        val sut = readySpark(balanceSats = 50_000uL)

        val leg = sut.withdrawalRouteLeg(contactEndpoint, 20_000uL).getOrThrow()

        assertEquals(
            ServiceRouteLeg(
                service = "spark",
                endpoint = contactEndpoint,
                deliveredSat = 20_000uL,
                debitSat = 20_500uL,
                atomicity = "trusted",
                validUntilUnix = 1_900_000_000uL,
            ),
            leg,
        )
    }

    @Test
    fun `offers no route leg the balance cannot cover with its fee`() = test {
        val sut = readySpark(balanceSats = 20_499uL)

        assertEquals(null, sut.withdrawalRouteLeg(contactEndpoint, 20_000uL).getOrThrow())
    }

    @Test
    fun `quotes a route leg whose debit takes the whole balance`() = test {
        val sut = readySpark(balanceSats = 20_500uL)

        assertEquals(20_500uL, sut.withdrawalRouteLeg(contactEndpoint, 20_000uL).getOrThrow()?.debitSat)
    }

    @Test
    fun `tells a spark withdrawal from a transaction ldk holds`() = test {
        whenever { sparkService.listPayments() }.thenReturn(listOf(contactWithdrawal()))
        val sut = readySpark(balanceSats = 50_000uL)

        assertTrue(sut.isWithdrawal("withdrawal", isSent = true))
        assertFalse(sut.isWithdrawal("ldk-send", isSent = true))
    }

    @Test
    fun `the savings receipt of a spark withdrawal stays boostable`() = test {
        whenever { sparkService.listPayments() }.thenReturn(listOf(contactWithdrawal()))
        val sut = readySpark(balanceSats = 50_000uL)

        assertFalse(sut.isWithdrawal("withdrawal", isSent = false), "Savings owns the output the withdrawal pays")
    }

    @Test
    fun `records a withdrawal to a contact with its contact and fee`() = test {
        val sut = journaledSpark()
        whenever { sparkService.send(any()) }.thenReturn(contactWithdrawal())

        assertEquals("withdrawal", sut.withdraw("bcrt1qcontact", 20_000uL, "pubkycontact").getOrThrow())

        val activity = argumentCaptor<Activity>()
        verify(activityRepo).upsertActivity(activity.capture())
        val onchain = assertIs<Activity.Onchain>(activity.firstValue).v1
        assertEquals(ActivityPaymentType.SENT, onchain.txType)
        assertEquals(20_000uL, onchain.value)
        assertEquals(300uL, onchain.fee)
        assertEquals("pubkycontact", onchain.contact)
        assertNull(cacheData.value.pendingSparkWithdrawal)
    }

    @Test
    fun `an accepted withdrawal stays sent after a restart whose list lacks it`() = test {
        journaledSpark().also { whenever { sparkService.send(any()) }.thenReturn(contactWithdrawal()) }
            .withdraw("bcrt1qcontact", 20_000uL, "pubkycontact").getOrThrow()
        assertNull(cacheData.value.pendingSparkWithdrawal)
        // After a restart before the request's proof completed, Spark's list lacks the withdrawal.
        whenever { sparkService.listPayments() }.thenReturn(emptyList())
        val restarted = createSut()

        whenever { sparkService.getInfo() }
            .thenReturn(info(50_000uL).copy(lastSyncError = SyncError.Network("operators unreachable")))
        restarted.start(0).getOrThrow()
        assertEquals(
            SparkWithdrawalOutcome.Sent("withdrawal"),
            restarted.withdrawalOutcome("bcrt1qcontact", 20_000uL, emptySet()),
            "A failed sync after the restart must not release a request Spark accepted",
        )

        whenever { sparkService.getInfo() }.thenReturn(info(50_000uL))
        restarted.start(0).getOrThrow()
        assertEquals(
            SparkWithdrawalOutcome.Sent("withdrawal"),
            restarted.withdrawalOutcome("bcrt1qcontact", 20_000uL, emptySet()),
            "A list that lacks an accepted withdrawal is no proof it never sent",
        )
        assertNotEquals(
            SparkWithdrawalOutcome.Sent("withdrawal"),
            restarted.withdrawalOutcome("bcrt1qcontact", 20_000uL, setOf("withdrawal")),
            "An earlier attempt's withdrawal never settles a new one",
        )
    }

    @Test
    fun `a cached withdrawal never settles a retry after a restart whose list lacks it`() = test {
        journaledSpark().also { whenever { sparkService.send(any()) }.thenReturn(contactWithdrawal()) }
            .withdraw("bcrt1qcontact", 20_000uL, "pubkycontact").getOrThrow()
        whenever { sparkService.listPayments() }.thenReturn(emptyList())
        val restarted = createSut().also { it.start(0).getOrThrow() }

        val earlierTxids = restarted.withdrawalTxidsTo("bcrt1qcontact")

        assertNotEquals(
            SparkWithdrawalOutcome.Sent("withdrawal"),
            restarted.withdrawalOutcome("bcrt1qcontact", 20_000uL, earlierTxids),
        )
    }

    @Test
    fun `refuses a withdrawal while an earlier one is unsettled`() = test {
        val sut = journaledSpark()
        cacheData.update { it.copy(pendingSparkWithdrawal = PendingSparkWithdrawal("bcrt1qcontact", 20_000uL, null)) }

        assertIs<SparkError.WithdrawalPending>(sut.withdraw("bcrt1qcontact", 20_000uL, null).exceptionOrNull())
        verify(sparkService, never()).send(any())
    }

    @Test
    fun `settles a journaled withdrawal from Spark's list on start`() = test {
        cacheData.update {
            it.copy(pendingSparkWithdrawal = PendingSparkWithdrawal("bcrt1qcontact", 20_000uL, "pubkycontact"))
        }
        whenever { sparkService.listPayments() }.thenReturn(listOf(contactWithdrawal()))

        journaledSpark()

        val activity = argumentCaptor<Activity>()
        verify(activityRepo).upsertActivity(activity.capture())
        assertEquals("pubkycontact", assertIs<Activity.Onchain>(activity.firstValue).v1.contact)
        assertNull(cacheData.value.pendingSparkWithdrawal)
    }

    @Test
    fun `drops a journaled withdrawal Spark never sent`() = test {
        cacheData.update { it.copy(pendingSparkWithdrawal = PendingSparkWithdrawal("bcrt1qcontact", 20_000uL, null)) }
        journaledSpark()

        verify(activityRepo, never()).upsertActivity(any())
        assertNull(cacheData.value.pendingSparkWithdrawal)
    }

    @Test
    fun `keeps a journaled withdrawal while the sync on start never reached spark`() = test {
        cacheData.update { it.copy(pendingSparkWithdrawal = PendingSparkWithdrawal("bcrt1qcontact", 20_000uL, null)) }
        whenever { cacheStore.update(any()) }.doSuspendableAnswer {
            cacheData.update(it.getArgument<(AppCacheData) -> AppCacheData>(0))
        }
        whenever(sparkService.isConnected).thenReturn(true)
        // The sync call returns, but Spark could not reach its operators, so its list may lack the withdrawal.
        whenever { sparkService.getInfo() }
            .thenReturn(info(50_000uL).copy(lastSyncError = SyncError.Network("operators unreachable")))

        createSut().start(0).getOrThrow()

        assertNotNull(
            cacheData.value.pendingSparkWithdrawal,
            "A sync that never reached Spark can't show it never sent",
        )
    }

    @Test
    fun `keeps an unlisted withdrawal open until its grace period passes`() = test {
        val sut = journaledSpark()
        whenever(sparkService.send(any())).doSuspendableAnswer { throw SdkException.Generic("timeout") }

        assertTrue(sut.withdraw("bcrt1qcontact", 20_000uL, null).isFailure)

        assertEquals(now.epochSeconds, cacheData.value.pendingSparkWithdrawal?.startedAt)
        assertEquals(
            SparkWithdrawalOutcome.Unknown,
            sut.withdrawalOutcome("bcrt1qcontact", 20_000uL, emptySet()),
            "A sync right after the send may not list the withdrawal yet, so it is never released as unsent",
        )
        cacheData.update {
            it.copy(
                pendingSparkWithdrawal = it.pendingSparkWithdrawal?.copy(
                    startedAt = now.epochSeconds - 10.minutes.inWholeSeconds,
                ),
            )
        }
        sut.start(0).getOrThrow()
        assertNull(cacheData.value.pendingSparkWithdrawal)
    }

    @Test
    fun `keeps an aged failed withdrawal's journal while the sync after it never reached spark`() = test {
        val sut = journaledSpark()
        whenever(sparkService.send(any())).doSuspendableAnswer {
            now += 11.minutes
            throw SdkException.Generic("timeout")
        }
        // The sync call returns, but the SDK keeps the failed Spark sync in its info.
        whenever { sparkService.getInfo() }.thenReturn(
            info(50_000uL).copy(
                lastSyncError = SyncError.Network("operators unreachable"),
                lastSyncedAt = (now + 1.hours).epochSeconds.toULong(),
            )
        )

        assertTrue(sut.withdraw("bcrt1qcontact", 20_000uL, null).isFailure)

        assertNotNull(
            cacheData.value.pendingSparkWithdrawal,
            "A sync that never reached Spark can't show the aged withdrawal was never sent",
        )
    }

    @Test
    fun `a journal that closes tells the pending send how its withdrawal ended`() = test {
        val settlements = mutableListOf<SparkWithdrawalSettlement>()
        cacheData.update { it.copy(pendingSparkWithdrawal = PendingSparkWithdrawal("bcrt1qcontact", 20_000uL, null)) }
        whenever { sparkService.listPayments() }.thenReturn(listOf(contactWithdrawal()))
        val sut = createSut()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            sut.withdrawalSettlements.collect { settlements += it }
        }
        whenever { cacheStore.update(any()) }.doSuspendableAnswer {
            cacheData.update(it.getArgument<(AppCacheData) -> AppCacheData>(0))
        }
        whenever { sparkService.getInfo() }.thenReturn(info(50_000uL))
        whenever(sparkService.isConnected).thenReturn(true)

        sut.start(0).getOrThrow()
        cacheData.update { it.copy(pendingSparkWithdrawal = PendingSparkWithdrawal("bcrt1qcontact", 5_000uL, null)) }
        sut.start(0).getOrThrow()

        assertEquals(
            listOf(
                SparkWithdrawalSettlement("bcrt1qcontact", 20_000uL, "withdrawal"),
                SparkWithdrawalSettlement("bcrt1qcontact", 5_000uL, null),
            ),
            settlements,
        )
    }

    @Test
    fun `a sync in a long foreground session closes an expired unlisted withdrawal`() = test {
        val sut = journaledSpark()
        cacheData.update { it.copy(pendingSparkWithdrawal = PendingSparkWithdrawal("bcrt1qcontact", 20_000uL, null)) }
        whenever { sparkService.getInfo() }.thenReturn(info(50_000uL).copy(lastSyncedAt = now.epochSeconds.toULong()))

        sparkEvents.emit(SdkEvent.Synced)
        runCurrent()

        assertNull(cacheData.value.pendingSparkWithdrawal, "An expired journal must not block later withdrawals")
        assertNull(sut.withdraw("bcrt1qcontact", 20_000uL, null).exceptionOrNull() as? SparkError.WithdrawalPending)
    }

    @Test
    fun `a synced event after a sync that never reached spark keeps an expired unlisted withdrawal`() = test {
        journaledSpark()
        cacheData.update { it.copy(pendingSparkWithdrawal = PendingSparkWithdrawal("bcrt1qcontact", 20_000uL, null)) }
        // Spark emits Synced after a sync whose Spark leg failed too, and keeps that failure in its info.
        whenever { sparkService.getInfo() }.thenReturn(
            info(50_000uL).copy(
                lastSyncError = SyncError.Network("operators unreachable"),
                lastSyncedAt = now.epochSeconds.toULong(),
            )
        )

        sparkEvents.emit(SdkEvent.Synced)
        runCurrent()

        assertNotNull(
            cacheData.value.pendingSparkWithdrawal,
            "A sync that never reached Spark can't show the withdrawal was never sent",
        )
    }

    @Test
    fun `a payment event keeps the journal until spark lists the withdrawal`() = test {
        val sut = journaledSpark()
        cacheData.update {
            it.copy(pendingSparkWithdrawal = PendingSparkWithdrawal("bcrt1qcontact", 20_000uL, "pubkycontact"))
        }

        sparkEvents.emit(SdkEvent.PaymentSucceeded(contactWithdrawal()))
        runCurrent()

        verify(activityRepo).upsertActivity(any())
        assertEquals(
            SparkWithdrawalOutcome.Unknown,
            sut.withdrawalOutcome("bcrt1qcontact", 20_000uL, emptySet()),
            "A list that lags the event must not read the withdrawal as unsent",
        )
        whenever { sparkService.listPayments() }.thenReturn(listOf(contactWithdrawal()))
        sut.start(0).getOrThrow()
        assertNull(cacheData.value.pendingSparkWithdrawal)
    }

    @Test
    fun `a stale settle never clears a newer withdrawal's journal`() = test {
        val newer = PendingSparkWithdrawal("bcrt1qcontact", 30_000uL, "pubkycontact", startedAt = now.epochSeconds)
        cacheData.update {
            it.copy(pendingSparkWithdrawal = PendingSparkWithdrawal("bcrt1qcontact", 20_000uL, "pubkycontact"))
        }
        whenever { sparkService.listPayments() }.thenReturn(listOf(contactWithdrawal()))
        // Another settle closes the older journal and the newer withdrawal writes its own while this one saves.
        whenever { activityRepo.upsertActivity(any()) }.doSuspendableAnswer {
            cacheData.update { it.copy(pendingSparkWithdrawal = newer) }
            Result.success(Unit)
        }

        journaledSpark()

        assertEquals(newer, cacheData.value.pendingSparkWithdrawal, "Losing the newer journal could pay it twice")
    }

    @Test
    fun `a withdrawal that failed to save is written again on refresh and settles its journal once`() = test {
        cacheData.update {
            it.copy(pendingSparkWithdrawal = PendingSparkWithdrawal("bcrt1qcontact", 20_000uL, "pubkycontact"))
        }
        whenever { sparkService.listPayments() }.thenReturn(listOf(contactWithdrawal()))
        whenever { cacheStore.update(any()) }.doSuspendableAnswer {
            cacheData.update(it.getArgument<(AppCacheData) -> AppCacheData>(0))
        }
        whenever { activityRepo.upsertActivity(any()) }
            .thenReturn(Result.failure(IllegalStateException("write failed")), Result.success(Unit))
        whenever { sparkService.getInfo() }.thenReturn(info(50_000uL))
        whenever(sparkService.isConnected).thenReturn(true)
        val settlements = mutableListOf<SparkWithdrawalSettlement>()
        val sut = createSut()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            sut.withdrawalSettlements.collect { settlements += it }
        }

        sut.start(0).getOrThrow()
        assertEquals(
            PendingSparkWithdrawal("bcrt1qcontact", 20_000uL, "pubkycontact"),
            cacheData.value.pendingSparkWithdrawal,
            "A withdrawal whose activity failed to save keeps its journal",
        )
        sut.refreshState().getOrThrow()
        sut.refreshState().getOrThrow()

        verify(activityRepo, times(2)).upsertActivity(any())
        assertNull(cacheData.value.pendingSparkWithdrawal)
        assertEquals(listOf(SparkWithdrawalSettlement("bcrt1qcontact", 20_000uL, "withdrawal")), settlements)
    }

    @Test
    fun `a stale settle never tells the newer withdrawal's send it ended`() = test {
        // A retry to the same address and amount, so a settlement for the older one would read as the newer one's.
        val newer = PendingSparkWithdrawal(
            "bcrt1qcontact",
            20_000uL,
            "pubkycontact",
            earlierTxids = setOf("withdrawal"),
            startedAt = now.epochSeconds,
        )
        cacheData.update {
            it.copy(pendingSparkWithdrawal = PendingSparkWithdrawal("bcrt1qcontact", 20_000uL, "pubkycontact"))
        }
        whenever { sparkService.listPayments() }.thenReturn(listOf(contactWithdrawal()))
        whenever { cacheStore.update(any()) }.doSuspendableAnswer {
            cacheData.update(it.getArgument<(AppCacheData) -> AppCacheData>(0))
        }
        // Another settle closes the older journal and the newer withdrawal writes its own while this one saves.
        whenever { activityRepo.upsertActivity(any()) }.doSuspendableAnswer {
            cacheData.update { it.copy(pendingSparkWithdrawal = newer) }
            Result.success(Unit)
        }
        whenever { sparkService.getInfo() }.thenReturn(info(50_000uL))
        whenever(sparkService.isConnected).thenReturn(true)
        val settlements = mutableListOf<SparkWithdrawalSettlement>()
        val sut = createSut()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            sut.withdrawalSettlements.collect { settlements += it }
        }

        sut.start(0).getOrThrow()

        assertEquals(newer, cacheData.value.pendingSparkWithdrawal)
        assertEquals(emptyList(), settlements, "The older txid would settle the newer send still in flight")
    }

    @Test
    fun `keeps a journaled withdrawal Spark lists when its activity fails to save on start`() = test {
        cacheData.update {
            it.copy(pendingSparkWithdrawal = PendingSparkWithdrawal("bcrt1qcontact", 20_000uL, "pubkycontact"))
        }
        whenever { sparkService.listPayments() }.thenReturn(listOf(contactWithdrawal()))
        whenever { activityRepo.upsertActivity(any()) }.thenReturn(Result.failure(AppError("disk full")))

        journaledSpark()

        assertEquals("pubkycontact", cacheData.value.pendingSparkWithdrawal?.contact)
    }

    @Test
    fun `a withdrawal refused right before it sends writes no journal`() = test {
        val sut = journaledSpark()

        val result = sut.withdraw("bcrt1qcontact", 20_000uL, null, onBeforeSend = { false })

        assertIs<PaymentAbortedBeforeSend>(result.exceptionOrNull())
        verify(sparkService, never()).send(any())
        assertNull(cacheData.value.pendingSparkWithdrawal)
    }

    @Test
    fun `a withdrawal whose deadline passes before it sends never leaves`() = test {
        val sut = journaledSpark()

        val result = sut.withdraw(
            "bcrt1qcontact",
            20_000uL,
            null,
            paymentDeadlineAt = now + 1.minutes,
            onBeforeSend = {
                // The quote's deadline passes while the allowance and the request are checked.
                now += 2.minutes
                true
            },
        )

        assertIs<ServiceError.PaymentDeadlineExpired>(result.exceptionOrNull())
        verify(sparkService, never()).send(any())
        assertNull(cacheData.value.pendingSparkWithdrawal)
    }

    @Test
    fun `a withdrawal whose deadline passes while its journal is written never leaves`() = test {
        val sut = journaledSpark()
        whenever { cacheStore.update(any()) }.doSuspendableAnswer {
            cacheData.update(it.getArgument<(AppCacheData) -> AppCacheData>(0))
            if (cacheData.value.pendingSparkWithdrawal != null) now += 2.minutes
        }
        whenever { sparkService.send(any()) }.thenReturn(contactWithdrawal())

        val result = sut.withdraw("bcrt1qcontact", 20_000uL, null, paymentDeadlineAt = now + 1.minutes)

        assertIs<ServiceError.PaymentDeadlineExpired>(result.exceptionOrNull())
        verify(sparkService, never()).send(any())
        assertNull(cacheData.value.pendingSparkWithdrawal)
    }

    @Test
    fun `a withdrawal whose proof is starting reads as in flight before its journal is written`() = test {
        val sut = journaledSpark()
        var outcomeWhileStarting: SparkWithdrawalOutcome? = null

        sut.withdraw("bcrt1qcontact", 20_000uL, null, onBeforeSend = {
            // A reconcile in this window must not drop the proof the send is about to pay.
            outcomeWhileStarting = sut.withdrawalOutcome("bcrt1qcontact", 20_000uL, emptySet())
            true
        })

        assertEquals(SparkWithdrawalOutcome.Unknown, outcomeWhileStarting)
    }

    @Test
    fun `a withdrawal spark returns as failed is not reported as sent`() = test {
        val sut = journaledSpark()
        val failed = contactWithdrawal().copy(status = PaymentStatus.FAILED)
        whenever(sparkService.send(any())).thenReturn(failed)
        whenever(sparkService.listPayments()).thenReturn(emptyList(), listOf(failed))

        val result = sut.withdraw("bcrt1qcontact", 20_000uL, "pubkycontact")

        assertNull(result.getOrNull())
        assertIs<SparkError.WithdrawalNotSent>(result.exceptionOrNull())
        assertNull(cacheData.value.pendingSparkWithdrawal)
        assertEquals(
            SparkWithdrawalOutcome.Unsent,
            sut.withdrawalOutcome("bcrt1qcontact", 20_000uL, emptySet()),
            "The proof, private list and allowance attempt are released as for any unsent withdrawal",
        )
    }

    @Test
    fun `a pending withdrawal stays open until spark completes it`() = test {
        val sut = journaledSpark()
        val pending = contactWithdrawal().copy(status = PaymentStatus.PENDING)
        whenever(sparkService.send(any())).thenReturn(pending)
        whenever(sparkService.listPayments()).thenReturn(emptyList(), listOf(pending))

        val result = sut.withdraw("bcrt1qcontact", 20_000uL, "pubkycontact")

        assertNull(result.getOrNull(), "Spark can still fail a withdrawal until its provider accepts the payout")
        assertEquals("pubkycontact", cacheData.value.pendingSparkWithdrawal?.contact)
        assertEquals(SparkWithdrawalOutcome.Unknown, sut.withdrawalOutcome("bcrt1qcontact", 20_000uL, emptySet()))

        whenever(sparkService.listPayments()).thenReturn(listOf(contactWithdrawal()))
        sut.start(0).getOrThrow()

        assertNull(cacheData.value.pendingSparkWithdrawal)
        assertEquals(
            SparkWithdrawalOutcome.Sent("withdrawal"),
            sut.withdrawalOutcome("bcrt1qcontact", 20_000uL, emptySet()),
        )
    }

    @Test
    fun `a pending withdrawal spark later fails closes as unsent`() = test {
        val sut = journaledSpark()
        val pending = contactWithdrawal().copy(status = PaymentStatus.PENDING)
        whenever(sparkService.send(any())).thenReturn(pending)
        whenever(sparkService.listPayments()).thenReturn(emptyList(), listOf(pending))
        sut.withdraw("bcrt1qcontact", 20_000uL, "pubkycontact")

        whenever(sparkService.listPayments()).thenReturn(listOf(pending.copy(status = PaymentStatus.FAILED)))
        sut.start(0).getOrThrow()

        assertNull(cacheData.value.pendingSparkWithdrawal)
        assertEquals(SparkWithdrawalOutcome.Unsent, sut.withdrawalOutcome("bcrt1qcontact", 20_000uL, emptySet()))
    }

    @Test
    fun `a withdrawal spark refuses for its balance closes its journal at once`() = test {
        val sut = journaledSpark()
        whenever { sparkService.send(any()) }.thenAnswer { throw SdkException.InsufficientFunds(null) }
        clearInvocations(sparkService)

        val result = sut.withdraw("bcrt1qcontact", 20_000uL, "pubkycontact")

        // Spark picks the leaves to spend before anything reaches its operators, so nothing left the wallet.
        assertIs<SparkError.SendRefused>(result.exceptionOrNull())
        assertNull(cacheData.value.pendingSparkWithdrawal)
        verify(sparkService, never()).syncWallet()
    }

    @Test
    fun `a withdrawal to a contact is not marked as a transfer`() = test {
        val sent = OnchainActivity.create(
            id = "withdrawal",
            txType = ActivityPaymentType.SENT,
            txId = "withdrawal",
            value = 20_000uL,
            fee = 500uL,
            address = "bcrt1qcontact",
            timestamp = 1uL,
        )
        whenever { activityRepo.getOnchainActivityByTxId(eq("withdrawal"), any()) }.thenReturn(sent)
        whenever { sparkService.listPayments() }.thenReturn(listOf(contactWithdrawal()))
        val sut = journaledSpark()

        assertTrue(sut.markWithdrawalAsTransfer("withdrawal"))
        verify(activityRepo, never()).updateActivity(any(), any(), any())
    }

    @Test
    fun `lists a withdrawal to a contact with the sends, not as a transfer to savings`() = test {
        val sent = OnchainActivity.create(
            id = "withdrawal",
            txType = ActivityPaymentType.SENT,
            txId = "withdrawal",
            value = 20_000uL,
            fee = 300uL,
            address = "bcrt1qcontact",
            timestamp = 1uL,
        )
        whenever { activityRepo.getActivity(eq("withdrawal"), any()) }
            .thenReturn(Result.success(Activity.Onchain(sent)))
        whenever { sparkService.listPayments() }.thenReturn(listOf(contactWithdrawal()))

        val sut = journaledSpark()

        assertTrue(sut.state.value.withdrawals.isEmpty())
    }

    @Test
    fun `refuses a withdrawal whose fee rose above the confirmed debit`() = test {
        val sut = journaledSpark()

        val result = sut.withdraw("bcrt1qcontact", 20_000uL, null, maxDebitSats = 20_499uL)

        assertIs<SparkError.WithdrawalFeeChanged>(result.exceptionOrNull())
        verify(sparkService, never()).send(any())
        assertNull(cacheData.value.pendingSparkWithdrawal)
    }

    @Test
    fun `keeps the journal when the withdrawal's activity fails to save`() = test {
        val sut = journaledSpark()
        whenever { activityRepo.upsertActivity(any()) }.thenReturn(Result.failure(AppError("disk full")))
        whenever { sparkService.send(any()) }.thenReturn(contactWithdrawal())

        sut.withdraw("bcrt1qcontact", 20_000uL, "pubkycontact").getOrThrow()

        assertEquals("pubkycontact", cacheData.value.pendingSparkWithdrawal?.contact)
    }

    @Test
    fun `keeps the journal when the withdrawal's tags fail to save`() = test {
        val sut = journaledSpark()
        whenever { activityRepo.addTagsToActivity(any(), any(), any()) }
            .thenReturn(Result.failure(AppError("disk full")))
        whenever { sparkService.send(any()) }.thenReturn(contactWithdrawal())

        sut.withdraw("bcrt1qcontact", 20_000uL, "pubkycontact", tags = listOf("rent")).getOrThrow()

        assertEquals(listOf("rent"), cacheData.value.pendingSparkWithdrawal?.tags)
    }

    @Test
    fun `settles the journal of a deleted withdrawal it cannot tag`() = test {
        val sut = journaledSpark()
        whenever { activityRepo.upsertActivity(any()) }.thenReturn(Result.failure(ActivityDeletedError("withdrawal")))
        whenever { activityRepo.addTagsToActivity(any(), any(), any()) }
            .thenReturn(Result.failure(AppError("not found")))
        whenever { sparkService.send(any()) }.thenReturn(contactWithdrawal())

        sut.withdraw("bcrt1qcontact", 20_000uL, "pubkycontact", tags = listOf("rent")).getOrThrow()

        assertNull(cacheData.value.pendingSparkWithdrawal)
    }

    @Test
    fun `an earlier withdrawal to the same address never settles a new attempt`() = test {
        whenever { sparkService.listPayments() }.thenReturn(listOf(contactWithdrawal()))
        val sut = journaledSpark()
        cacheData.update {
            it.copy(
                pendingSparkWithdrawal = PendingSparkWithdrawal("bcrt1qcontact", 20_000uL, null, setOf("withdrawal")),
            )
        }

        assertEquals(
            SparkWithdrawalOutcome.Unknown,
            sut.withdrawalOutcome("bcrt1qcontact", 20_000uL, setOf("withdrawal")),
        )
        cacheData.update { it.copy(pendingSparkWithdrawal = null) }
        assertEquals(
            SparkWithdrawalOutcome.Unsent,
            sut.withdrawalOutcome("bcrt1qcontact", 20_000uL, setOf("withdrawal")),
        )
        assertEquals(
            SparkWithdrawalOutcome.Sent("withdrawal"),
            sut.withdrawalOutcome("bcrt1qcontact", 20_000uL, emptySet()),
        )
    }

    @Test
    fun `tags a recovered withdrawal with the tags picked on confirmation`() = test {
        whenever { activityRepo.addTagsToActivity(any(), any(), any()) }.thenReturn(Result.success(Unit))
        cacheData.update {
            it.copy(
                pendingSparkWithdrawal = PendingSparkWithdrawal("bcrt1qcontact", 20_000uL, null, tags = listOf("rent")),
            )
        }
        whenever { sparkService.listPayments() }.thenReturn(listOf(contactWithdrawal()))

        journaledSpark()

        verify(activityRepo).addTagsToActivity(eq("withdrawal"), eq(listOf("rent")), any())
        assertNull(cacheData.value.pendingSparkWithdrawal)
    }

    @Test
    fun `an older open journal refuses a new withdrawal before its proof can start`() = test {
        val sut = journaledSpark()
        cacheData.update { it.copy(pendingSparkWithdrawal = PendingSparkWithdrawal("bcrt1qcontact", 5_000uL, null)) }
        var checked = false

        val result = sut.withdraw("bcrt1qcontact", 20_000uL, null, onBeforeSend = {
            checked = true
            true
        })

        assertIs<SparkError.WithdrawalPending>(result.exceptionOrNull())
        assertFalse(checked)
        verify(sparkService, never()).prepareSend(any(), anyOrNull(), anyOrNull())
        assertEquals(5_000uL, cacheData.value.pendingSparkWithdrawal?.amountSats)
    }

    @Test
    fun `a second withdrawal started while the first prepares is refused`() = test {
        val sut = journaledSpark()
        val prepared = sparkService.prepareSend("bcrt1qcontact", 20_000uL, null)
        val prepareGate = CompletableDeferred<Unit>()
        whenever { sparkService.prepareSend(eq("bcrt1qcontact"), eq(20_000uL), anyOrNull()) }.doSuspendableAnswer {
            prepareGate.await()
            prepared
        }
        whenever { sparkService.send(any()) }.thenReturn(contactWithdrawal())
        val first = async { sut.withdraw("bcrt1qcontact", 20_000uL, "first") }
        runCurrent()

        val second = async { sut.withdraw("bcrt1qcontact", 20_000uL, "second") }
        runCurrent()
        prepareGate.complete(Unit)

        assertIs<SparkError.WithdrawalPending>(second.await().exceptionOrNull())
        assertEquals("withdrawal", first.await().getOrThrow())
        verify(sparkService, times(1)).send(any())
    }

    @Test
    fun `a foreground during an in-flight withdrawal keeps its journal until spark lists it`() = test {
        val sut = journaledSpark()
        val sendGate = CompletableDeferred<Unit>()
        whenever { sparkService.send(any()) }.doSuspendableAnswer {
            sendGate.await()
            contactWithdrawal()
        }
        val withdrawal = async { sut.withdraw("bcrt1qcontact", 20_000uL, "pubkycontact") }
        runCurrent()
        assertEquals("pubkycontact", cacheData.value.pendingSparkWithdrawal?.contact)

        var offline = true
        whenever { sparkService.syncWallet() }.thenAnswer { if (offline) throw SdkException.Generic("offline") }
        sut.start(0).getOrThrow()
        assertEquals("pubkycontact", cacheData.value.pendingSparkWithdrawal?.contact)
        offline = false
        sut.start(0).getOrThrow()
        assertEquals("pubkycontact", cacheData.value.pendingSparkWithdrawal?.contact)

        sendGate.complete(Unit)
        assertEquals("withdrawal", withdrawal.await().getOrThrow())

        assertNull(cacheData.value.pendingSparkWithdrawal)
        val activity = argumentCaptor<Activity>()
        verify(activityRepo).upsertActivity(activity.capture())
        assertEquals("pubkycontact", assertIs<Activity.Onchain>(activity.firstValue).v1.contact)
    }

    private suspend fun journaledSpark(): SparkRepo {
        whenever { cacheStore.update(any()) }.doSuspendableAnswer {
            cacheData.update(it.getArgument<(AppCacheData) -> AppCacheData>(0))
        }
        return readySpark(balanceSats = 50_000uL)
    }

    private fun contactWithdrawal() = sentPayment(fees = 300).copy(
        id = "withdrawal-payment",
        amount = BigInteger.valueOf(20_000),
        method = PaymentMethod.WITHDRAW,
        details = PaymentDetails.Withdraw("withdrawal", 0u, "bcrt1qcontact"),
    )

    private val contactEndpoint = PaykitEndpoint(methodId = "btc-regtest-p2wpkh", value = "bcrt1qcontact")

    private suspend fun readySpark(balanceSats: ULong): SparkRepo {
        whenever { sparkService.getInfo() }.thenReturn(info(balanceSats))
        val speed = SendOnchainSpeedFeeQuote(userFeeSat = 300uL, l1BroadcastFeeSat = 200uL)
        val prepared = mock<PrepareSendPaymentResponse>()
        whenever(prepared.paymentMethod).thenReturn(
            SendPaymentMethod.BitcoinAddress(
                address = mock<BitcoinAddressDetails>(),
                feeQuote = SendOnchainFeeQuote("quote", 1_900_000_000uL, speed, speed, speed, isEstimate = false),
            ),
        )
        whenever { sparkService.prepareSend(eq("bcrt1qcontact"), eq(20_000uL), anyOrNull()) }.thenReturn(prepared)
        whenever(sparkService.isConnected).thenReturn(true)
        return createSut().also { it.start(0).getOrThrow() }
    }

    @Test
    fun `skips an exit backup from another network`() = test {
        val sut = createSut()

        sut.restoreExitBackup(SparkExitBackupV1(createdAt = 1, network = "mainnet", exitState = "state"), 0)
            .getOrThrow()

        assertFalse(File(Env.sparkExitRestorePath(0)).exists())
    }

    @Test
    fun `lists a broadcast deposit as pending until Spark lists it`() = test {
        val sut = createSut()

        sut.onDepositBroadcast("txid", 2_500uL)

        val deposit = sut.state.value.deposits.single()
        assertEquals("txid", deposit.txid)
        assertEquals(false, deposit.isClaimed)
        assertEquals(2_500uL, sut.state.value.pendingDepositSats)
    }

    @Test
    fun `instant claims early at once when the offer is within the ceiling`() = test {
        listDeposit()
        whenever { sparkService.fetchClaimQuote("txid", 0u) }.thenReturn(quote(earlyFee = 300uL))
        whenever { sparkService.claimDeposit("txid", 0u, 300uL) }.thenReturn(ClaimDepositOutcome.Submitted)
        whenever { sparkService.listPayments() }.thenReturn(emptyList(), listOf(depositPayment()))
        val sut = createSut()

        sut.followDepositClaim("txid", SparkClaimSpeed.Instant, ceilingSats = 500uL)
        advanceUntilIdle()

        verify(sparkService).claimDeposit("txid", 0u, 300uL)
        assertEquals(SparkDepositClaim.Claimed, sut.depositClaims.value["txid"])
    }

    @Test
    fun `instant asks the user when the early fee is over the ceiling`() = test {
        listDeposit()
        whenever { sparkService.fetchClaimQuote("txid", 0u) }.thenReturn(quote(earlyFee = 900uL))
        val sut = createSut()

        sut.followDepositClaim("txid", SparkClaimSpeed.Instant, ceilingSats = 500uL)
        advanceUntilIdle()

        val claim = assertIs<SparkDepositClaim.Repriced>(sut.depositClaims.value["txid"])
        assertEquals(900uL, claim.offer.earlyFeeSats)
        verify(sparkService, never()).claimDeposit(any(), any(), any())
    }

    @Test
    fun `paying a repriced claim claims at the new fee`() = test {
        listDeposit()
        whenever { sparkService.fetchClaimQuote("txid", 0u) }.thenReturn(quote(earlyFee = 900uL))
        whenever { sparkService.claimDeposit("txid", 0u, 900uL) }.thenReturn(ClaimDepositOutcome.Submitted)
        val sut = createSut()
        sut.followDepositClaim("txid", SparkClaimSpeed.Instant, ceilingSats = 500uL)
        advanceUntilIdle()

        sut.payRepricedClaim("txid")
        advanceUntilIdle()

        verify(sparkService).claimDeposit("txid", 0u, 900uL)
        assertEquals(SparkDepositClaim.Adding, sut.depositClaims.value["txid"])
    }

    @Test
    fun `instant shows the new price when the claim comes back repriced`() = test {
        listDeposit()
        whenever { sparkService.fetchClaimQuote("txid", 0u) }.thenReturn(quote(earlyFee = 300uL))
        whenever { sparkService.claimDeposit("txid", 0u, 300uL) }.thenReturn(
            ClaimDepositOutcome.Deferred(
                ClaimDeferredReason.MaxFeeExceeded(requiredFeeSats = 450uL, maxFeeSats = 300uL)
            )
        )
        val sut = createSut()

        sut.followDepositClaim("txid", SparkClaimSpeed.Instant, ceilingSats = 500uL)
        advanceUntilIdle()

        val claim = assertIs<SparkDepositClaim.Repriced>(sut.depositClaims.value["txid"])
        assertEquals(450uL, claim.offer.earlyFeeSats)
    }

    @Test
    fun `wait after a claim time reprice clears the early claim cap`() = test {
        listDeposit()
        whenever { sparkService.fetchClaimQuote("txid", 0u) }.thenReturn(quote(earlyFee = 300uL))
        whenever { sparkService.claimDeposit("txid", 0u, 300uL) }.thenReturn(
            ClaimDepositOutcome.Deferred(
                ClaimDeferredReason.MaxFeeExceeded(requiredFeeSats = 450uL, maxFeeSats = 300uL)
            )
        )
        val sut = createSut()
        sut.followDepositClaim("txid", SparkClaimSpeed.Instant, ceilingSats = 500uL)
        advanceUntilIdle()

        sut.waitForMatureClaim("txid")
        advanceUntilIdle()

        assertEquals(SparkDepositClaim.Later, sut.depositClaims.value["txid"])
        verify(sparkService).releaseDepositClaimFee("txid", 0u)
    }

    @Test
    fun `wait clears the early claim cap again when another claim held the deposit`() = test {
        listDeposit()
        whenever { sparkService.fetchClaimQuote("txid", 0u) }.thenReturn(quote(earlyFee = 300uL))
        whenever { sparkService.claimDeposit("txid", 0u, 300uL) }.thenReturn(
            ClaimDepositOutcome.Deferred(
                ClaimDeferredReason.MaxFeeExceeded(requiredFeeSats = 450uL, maxFeeSats = 300uL)
            )
        )
        // The first call meets Spark's own sync claiming the deposit, which leaves the cap in place.
        whenever { sparkService.releaseDepositClaimFee("txid", 0u) }
            .thenAnswer { throw SdkException.DepositClaimInProgress("txid", 0u, "") }
            .thenReturn(Unit)
        val sut = createSut()
        sut.followDepositClaim("txid", SparkClaimSpeed.Instant, ceilingSats = 500uL)
        advanceUntilIdle()

        sut.waitForMatureClaim("txid")
        advanceUntilIdle()

        verify(sparkService, times(2)).releaseDepositClaimFee("txid", 0u)
    }

    @Test
    fun `wait before any claim attempt leaves the deposit alone`() = test {
        listDeposit()
        whenever { sparkService.fetchClaimQuote("txid", 0u) }.thenReturn(quote(earlyFee = 900uL))
        val sut = createSut()
        sut.followDepositClaim("txid", SparkClaimSpeed.Instant, ceilingSats = 500uL)
        advanceUntilIdle()

        sut.waitForMatureClaim("txid")
        advanceUntilIdle()

        verify(sparkService, never()).releaseDepositClaimFee(any(), any())
    }

    @Test
    fun `attempted sends are unknown while spark can't list them`() = test {
        whenever(sparkService.isConnected).thenReturn(false)
        val sut = createSut()

        assertNull(sut.attemptedLightningPaymentHashes())

        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.listPayments() }.thenAnswer { throw SdkException.Generic("offline") }
        assertNull(sut.attemptedLightningPaymentHashes())
    }

    @Test
    fun `instant is unavailable when Spark offers only the standard claim`() = test {
        listDeposit()
        whenever { sparkService.fetchClaimQuote("txid", 0u) }.thenReturn(quote(earlyFee = null))
        val sut = createSut()

        sut.followDepositClaim("txid", SparkClaimSpeed.Instant, ceilingSats = 500uL)
        advanceUntilIdle()

        assertEquals(SparkDepositClaim.Unavailable, sut.depositClaims.value["txid"])
        verify(sparkService, never()).claimDeposit(any(), any(), any())
    }

    @Test
    fun `instant is unavailable when the provider declines the early claim`() = test {
        listDeposit()
        whenever { sparkService.fetchClaimQuote("txid", 0u) }.thenReturn(quote(earlyFee = 300uL))
        whenever { sparkService.claimDeposit("txid", 0u, 300uL) }.thenReturn(
            ClaimDepositOutcome.Deferred(ClaimDeferredReason.ProviderDeclined("no"))
        )
        val sut = createSut()

        sut.followDepositClaim("txid", SparkClaimSpeed.Instant, ceilingSats = 500uL)
        advanceUntilIdle()

        assertEquals(SparkDepositClaim.Unavailable, sut.depositClaims.value["txid"])
    }

    @Test
    fun `later leaves the deposit to the claim at maturity`() = test {
        val sut = createSut()

        sut.followDepositClaim("txid", SparkClaimSpeed.Later, ceilingSats = 500uL)
        advanceUntilIdle()

        assertEquals(SparkDepositClaim.Later, sut.depositClaims.value["txid"])
        verify(sparkService, never()).listUnclaimedDeposits()
        verify(sparkService, never()).claimDeposit(any(), any(), any())
    }

    @Test
    fun `a later deposit reads as claimed once spark claims it`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val sut = createSut()
        sut.start(0).getOrThrow()
        sut.followDepositClaim("txid", SparkClaimSpeed.Later, ceilingSats = 500uL)

        whenever { sparkService.listPayments() }.thenReturn(listOf(depositPayment()))
        sparkEvents.emit(SdkEvent.ClaimedDeposits(emptyList()))

        assertEquals(SparkDepositClaim.Claimed, sut.depositClaims.value["txid"])
    }

    @Test
    fun `a payment event settles even when the balance read fails`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val sut = createSut()
        sut.start(0).getOrThrow()
        val events = mutableListOf<Event>()
        backgroundScope.launch { sut.nodeEvents.collect { events += it } }
        runCurrent()
        whenever { sparkService.getInfo() }.thenAnswer { throw SdkException.Generic("offline") }

        sparkEvents.emit(SdkEvent.PaymentSucceeded(sentPayment(fees = 1)))
        runCurrent()

        assertEquals("hash", assertIs<Event.PaymentSuccessful>(events.single()).paymentHash)
    }

    @Test
    fun `a restore whose exit data spark rejects fails and keeps it staged`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.importExitState(any()) }.thenAnswer { throw SdkException.Generic("bad state") }
        val sut = createSut()

        val result = sut.restoreExitBackup(SparkExitBackupV1(createdAt = 1, network = "regtest", exitState = "s"), 0)

        assertTrue(result.isFailure)
        assertTrue(File(Env.sparkExitRestorePath(0)).exists())
    }

    @Test
    fun `a failed withdrawal keeps the balance shown while spark reads zero`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(info(10_000uL))
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }
            .thenReturn(withdrawal(expiresAt = now.epochSeconds.toULong() + 600uL))
        whenever { sparkService.send(any()) }.thenAnswer { throw SdkException.Generic("operators unreachable") }
        val sut = createSut()
        sut.refreshState().getOrThrow()

        assertTrue(sut.withdrawAll(ADDRESS, confirmedFeeSats = 500uL).isFailure)
        whenever { sparkService.getInfo() }.thenReturn(info(0uL))
        sut.refreshState().getOrThrow()

        assertEquals(10_000uL, sut.state.value.shownBalanceSats, "the balance read 0 after the failed withdrawal")
        assertEquals(0uL, sut.state.value.sendableSats, "a send was offered from the kept balance")
    }

    @Test
    fun `a synced read after a failed withdrawal clears the kept balance even when it reads zero`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(info(10_000uL))
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }
            .thenReturn(withdrawal(expiresAt = now.epochSeconds.toULong() + 600uL))
        whenever { sparkService.send(any()) }.thenAnswer { throw SdkException.Generic("operators unreachable") }
        val sut = createSut()
        sut.refreshState().getOrThrow()
        assertTrue(sut.withdrawAll(ADDRESS, confirmedFeeSats = 500uL).isFailure)

        whenever { sparkService.getInfo() }.thenReturn(info(0uL).copy(lastSyncedAt = now.epochSeconds.toULong() - 60uL))
        sut.refreshState().getOrThrow()
        val kept = sut.state.value.shownBalanceSats
        assertEquals(10_000uL, kept, "a sync from before the failure cleared the kept balance")

        whenever { sparkService.getInfo() }.thenReturn(info(0uL).copy(lastSyncedAt = now.epochSeconds.toULong() + 1uL))
        sut.refreshState().getOrThrow()
        assertEquals(0uL, sut.state.value.shownBalanceSats, "a sync after the failure kept the old balance")
    }

    @Test
    fun `a failed sync keeps the balance shown while spark reads zero`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(info(10_000uL))
        val sut = createSut()
        sut.refreshState().getOrThrow()

        val syncFailed = info(0uL).copy(lastSyncError = SyncError.Network("operators unreachable"))
        whenever { sparkService.getInfo() }.thenReturn(syncFailed)
        sut.refreshState().getOrThrow()
        assertEquals(10_000uL, sut.state.value.shownBalanceSats, "the balance read 0 while the sync failed")
        assertEquals(0uL, sut.state.value.sendableSats, "a send was offered from the kept balance")

        whenever { sparkService.getInfo() }.thenReturn(info(0uL))
        sut.refreshState().getOrThrow()
        assertEquals(0uL, sut.state.value.shownBalanceSats)
    }

    @Test
    fun `a deposit claimed early is listed only as claimed`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        listDeposit()
        whenever { sparkService.listPayments() }.thenReturn(listOf(depositPayment()))
        val sut = createSut()

        sut.refreshState().getOrThrow()

        val claimed = sut.state.value.deposits.map { it.isClaimed }
        assertEquals(listOf(true), claimed, "the claimed deposit is in progress too")
        assertEquals(0uL, sut.state.value.pendingDepositSats)
    }

    @Test
    fun `a sent withdrawal succeeds even when the refresh after it fails`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(info(10_000uL))
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }
            .thenReturn(withdrawal(expiresAt = now.epochSeconds.toULong() + 600uL))
        whenever { sparkService.send(any()) }.thenReturn(sentPayment(fees = 500))
        whenever { sparkService.listPayments() }.thenAnswer { throw SdkException.Generic("offline") }

        assertTrue(createSut().withdrawAll(ADDRESS, confirmedFeeSats = 500uL).isSuccess)
    }

    @Test
    fun `instant says Spark has not seen the deposit when it is never listed`() = test {
        val sut = createSut()

        sut.followDepositClaim("txid", SparkClaimSpeed.Instant, ceilingSats = 500uL)
        advanceUntilIdle()

        assertEquals(SparkDepositClaim.NotSeen, sut.depositClaims.value["txid"])
    }

    @Test
    fun `claim step follows the offer and the ceiling`() {
        val choice = SparkClaimOffer.Choice("txid", 0u, earlyFeeSats = 300uL, 0u, 198uL, 3u)

        assertEquals(SparkClaimStep.KeepLooking, SparkClaimStep.of(null, 500uL))
        assertEquals(SparkClaimStep.ClaimEarly(choice), SparkClaimStep.of(choice, 300uL))
        assertEquals(SparkClaimStep.Ask(choice), SparkClaimStep.of(choice, 299uL))
        assertEquals(
            SparkClaimStep.Unavailable,
            SparkClaimStep.of(SparkClaimOffer.Standard("txid", 0u, 198uL, 3u), 500uL)
        )
        assertEquals(SparkClaimStep.Adding, SparkClaimStep.of(SparkClaimOffer.Automatic("txid", 0u, 250uL, 0u), 500uL))
    }

    @Test
    fun `a receive recorded without its event gets its paykit contact`() = test {
        whenever { contactResolver.contactPublicKeyForPrivateInvoicePaymentHash("hash") }.thenReturn("pubky-contact")
        whenever { sparkService.listPayments() }.thenReturn(listOf(receivedPayment()))
        whenever(sparkService.isConnected).thenReturn(true)

        createSut().start(0).getOrThrow()

        val activity = argumentCaptor<Activity>()
        verify(activityRepo).upsertActivity(activity.capture())
        assertEquals("pubky-contact", (activity.firstValue as Activity.Lightning).v1.contact)
    }

    @Test
    fun `a receive whose contact the user detached stays without one`() = test {
        cacheData.value = AppCacheData(detachedActivityContacts = setOf(scopedActivityId(WalletScope.default, "hash")))
        whenever { contactResolver.contactPublicKeyForPrivateInvoicePaymentHash("hash") }.thenReturn("pubky-contact")
        whenever { sparkService.listPayments() }.thenReturn(listOf(receivedPayment()))
        whenever(sparkService.isConnected).thenReturn(true)

        createSut().start(0).getOrThrow()

        val activity = argumentCaptor<Activity>()
        verify(activityRepo).upsertActivity(activity.capture())
        assertNull((activity.firstValue as Activity.Lightning).v1.contact)
        verify(contactResolver, never()).contactPublicKeyForPrivateInvoicePaymentHash(any())
    }

    @Test
    fun `lightning send lookup reads spark's payments and waits until spark has synced them`() = test {
        val sut = createSut()
        assertEquals(SparkLightningSend.Unavailable, sut.lightningSend("hash"))

        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.syncWallet() }.thenAnswer { throw SdkException.Generic("offline") }
        val failed = sentPayment(fees = 0).copy(id = "failed", status = PaymentStatus.FAILED)
        sut.start(0).getOrThrow()
        whenever { sparkService.listPayments() }.thenReturn(listOf(failed, sentPayment(fees = 1)))
        // A restored wallet lists its payments only after its first sync.
        assertEquals(SparkLightningSend.Unavailable, sut.lightningSend("hash"))

        sparkEvents.emit(SdkEvent.Synced)
        assertEquals(SparkLightningSend.Found(PaymentStatus.COMPLETED, "preimage"), sut.lightningSend("HASH"))
        assertEquals(SparkLightningSend.NotFound, sut.lightningSend("other"))

        whenever { sparkService.listPayments() }.thenAnswer { throw SdkException.Generic("offline") }
        assertEquals(SparkLightningSend.Unavailable, sut.lightningSend("hash"))
    }

    @Test
    fun `a send spark refuses before it leaves is a typed refusal`() = test {
        val sut = createSut()
        assertIs<SparkError.SendRefused>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())

        whenever(sparkService.isConnected).thenReturn(true)
        val noRoute = SdkException.Generic("no route")
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenAnswer { throw noRoute }
        assertIs<SparkError.SendRefused>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())

        verify(sparkService, never()).send(any())
    }

    @Test
    fun `a cancel landing while spark prepares sends nothing`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        var cancelled = false
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenAnswer {
            cancelled = true
            prepared
        }
        val sut = createSut()

        val result = sut.payInvoice("lnbc1", 100uL, onBeforeSend = { !cancelled })

        assertIs<PaymentAbortedBeforeSend>(result.exceptionOrNull())
        verify(sparkService, never()).send(any())
    }

    @Test
    fun `an expired invoice spark refuses is a typed refusal`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val expired = SdkException.InvalidInput("Invoice has expired")
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenAnswer { throw expired }
        val sut = createSut()

        assertIs<SparkError.InvoiceExpired>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())

        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.thenAnswer { throw expired }
        assertIs<SparkError.InvoiceExpired>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())
    }

    @Test
    fun `an invoice that expired inside the spark send no longer holds the node`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.thenAnswer { throw SdkException.InvalidInput("Invoice has expired") }
        val sut = createSut()

        assertIs<SparkError.InvoiceExpired>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())

        verifyBlocking(cacheStore) { removeSparkSendHash("hash") }
    }

    @Test
    fun `insufficient funds inside the spark send is a refusal, not pending`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.thenAnswer { throw SdkException.InsufficientFunds(null) }
        val sut = createSut()

        val error = sut.payInvoice("lnbc1", 100uL).exceptionOrNull()

        assertIs<SparkError.SendRefused>(error)
        verifyBlocking(cacheStore) { removeSparkSendHash("hash") }
    }

    @Test
    fun `a spark send over the fee limit is refused before it leaves`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 2uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        val sut = createSut()

        assertIs<SparkError.SendRefused>(sut.payInvoice("lnbc1", 100uL, maxTotalFeeMsat = 1_999uL).exceptionOrNull())
        verify(sparkService, never()).send(any())

        whenever { sparkService.send(any()) }.thenReturn(sentPayment(fees = 2))
        assertEquals("hash", sut.payInvoice("lnbc1", 100uL, maxTotalFeeMsat = 2_000uL).getOrThrow())
    }

    @Test
    fun `a spark withdrawal pays the quote its fee estimate priced while it holds`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(info(10_000uL))
        val held = withdrawal(expiresAt = now.epochSeconds.toULong() + 600uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(held)
        whenever { sparkService.send(any()) }.thenReturn(sentPayment(fees = 0))
        val sut = createSut()

        assertEquals(500uL, sut.estimateWithdrawAllFeeSats(ADDRESS).getOrThrow())
        sut.withdrawAll(ADDRESS, confirmedFeeSats = 500uL).getOrThrow()

        verify(sparkService, times(1)).prepareSend(any(), anyOrNull(), anyOrNull())
        verify(sparkService).send(held)
    }

    @Test
    fun `a spark withdrawal asks again once its quote expired or the address changed`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(info(10_000uL))
        val expiring = withdrawal(expiresAt = now.epochSeconds.toULong() + 10uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(expiring)
        val sut = createSut()

        sut.estimateWithdrawAllFeeSats(ADDRESS).getOrThrow()
        sut.estimateWithdrawAllFeeSats(ADDRESS).getOrThrow()
        verify(sparkService, times(2)).prepareSend(any(), anyOrNull(), anyOrNull())

        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }
            .thenReturn(withdrawal(expiresAt = now.epochSeconds.toULong() + 600uL))
        sut.estimateWithdrawAllFeeSats(ADDRESS).getOrThrow()
        sut.estimateWithdrawAllFeeSats("bcrt1qother").getOrThrow()
        verify(sparkService, times(4)).prepareSend(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `a spark send past its deadline never leaves`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        val sut = createSut()

        val result = sut.payInvoice("lnbc1", 100uL, paymentDeadlineAt = now - 1.minutes)

        assertIs<ServiceError.PaymentDeadlineExpired>(result.exceptionOrNull())
        verify(sparkService, never()).send(any())
    }

    @Test
    fun `a failure inside the spark send is pending, not failed`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.thenAnswer { throw SdkException.Generic("timeout") }
        whenever { sparkService.getInfo() }.thenReturn(info(0uL).copy(lastSyncedAt = now.epochSeconds.toULong() + 1uL))

        val sut = createSut()
        val events = mutableListOf<Event>()
        backgroundScope.launch { sut.nodeEvents.collect { events += it } }
        runCurrent()

        val error = sut.payInvoice("lnbc1", 100uL).exceptionOrNull()
        advanceUntilIdle()

        assertEquals("hash", assertIs<PaymentPendingException>(error).paymentHash)
        // Spark synced after the failure and holds no such send, so nothing left and the pending send ends as failed.
        assertEquals("hash", assertIs<Event.PaymentFailed>(events.single()).paymentHash)
    }

    @Test
    fun `a max send keeps the routing fee spark quotes for its whole balance`() = test {
        whenever { sparkService.getInfo() }.thenReturn(info(100_000uL))
        val details = mock<Bolt11InvoiceDetails> { on { paymentHash } doReturn "hash" }
        val wholeBalance = PrepareSendPaymentResponse(
            paymentMethod = SendPaymentMethod.Bolt11Invoice(details, null, 400uL),
            amount = BigInteger.valueOf(99_600),
            tokenIdentifier = null,
            conversionEstimate = null,
            feePolicy = FeePolicy.FEES_INCLUDED,
        )
        whenever { sparkService.prepareSend("lnbc1", 100_000uL, FeePolicy.FEES_INCLUDED) }.thenReturn(wholeBalance)

        val reserve = createSut().maxSendReserveSats("lnbc1").getOrThrow()

        // The service fee alone leaves 99_750 sendable; routing on top caps the send at 99_600.
        assertEquals(99_600uL, SparkFees.sendableSats(100_000uL) - reserve)
    }

    @Test
    fun `an invoice is not sent again while its send is in flight`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        val sending = CompletableDeferred<Payment>()
        whenever { sparkService.send(any()) }.doSuspendableAnswer { sending.await() }
        val sut = createSut()

        val first = async { sut.payInvoice("lnbc1", 100uL) }
        runCurrent()
        val second = sut.payInvoice("lnbc1", 100uL)
        sending.complete(sentPayment(fees = 0))

        assertIs<PaymentPendingException>(second.exceptionOrNull())
        assertEquals("hash", first.await().getOrThrow())
        verify(sparkService, times(1)).send(any())
    }

    @Test
    fun `an invoice spark already paid or is paying is not sent again`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        val sut = createSut()
        sut.start(0).getOrThrow()

        whenever { sparkService.listPayments() }.thenReturn(listOf(sentPayment(fees = 0)))
        assertEquals("hash", sut.payInvoice("lnbc1", 100uL).getOrThrow())

        whenever { sparkService.listPayments() }
            .thenReturn(listOf(sentPayment(fees = 0).copy(status = PaymentStatus.PENDING)))
        assertIs<PaymentPendingException>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())

        verify(sparkService, never()).send(any())
    }

    @Test
    fun `a withdrawal whose fee rose above the confirmed one or leaves dust never leaves`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(info(10_000uL))
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }
            .thenReturn(withdrawal(expiresAt = now.epochSeconds.toULong() + 600uL))
        val sut = createSut()

        assertIs<SparkError.WithdrawalFeeChanged>(sut.withdrawAll(ADDRESS, confirmedFeeSats = 499uL).exceptionOrNull())

        whenever { sparkService.getInfo() }.thenReturn(info(1_000uL))
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }
            .thenReturn(withdrawal(expiresAt = now.epochSeconds.toULong() + 600uL, amountSats = 1_000))
        assertIs<SparkError.WithdrawalTooSmall>(sut.estimateWithdrawAllFeeSats(ADDRESS).exceptionOrNull())
        verify(sparkService, never()).send(any())
    }

    @Test
    fun `a balance spark refuses to quote as below the minimum is too small to withdraw`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(info(500uL))
        // What the SDK answers when even its cheapest fee would leave dust.
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenAnswer {
            throw SdkException.InvalidInput(
                "Amount is below the minimum of 330 sats required for this address after lowest fees of 1093 sats"
            )
        }
        val sut = createSut()

        assertIs<SparkError.WithdrawalTooSmall>(sut.estimateWithdrawAllFeeSats(ADDRESS).exceptionOrNull())
        assertIs<SparkError.WithdrawalTooSmall>(sut.withdrawAll(ADDRESS, confirmedFeeSats = 1_093uL).exceptionOrNull())

        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenAnswer {
            throw SdkException.InvalidInput("Invalid address: bad")
        }
        assertIs<SdkException.InvalidInput>(sut.estimateWithdrawAllFeeSats(ADDRESS).exceptionOrNull())
    }

    @Test
    fun `exit backup waits for spark's first sync`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.exportExitState() }.thenReturn("state")
        whenever { sparkService.syncWallet() }.thenAnswer { throw SdkException.Generic("offline") }
        val sut = createSut()
        sut.start(0).getOrThrow()

        assertNull(sut.exitBackup().getOrThrow())

        sparkEvents.emit(SdkEvent.Synced)
        assertEquals("state", sut.exitBackup().getOrThrow()?.exitState)
    }

    @Test
    fun `wipe fails when spark storage is not deleted and clears what spark held`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(info(5_000uL))
        val sut = createSut()
        sut.start(0).getOrThrow()
        sut.onDepositBroadcast("txid", 50_000uL)
        whenever { sparkService.wipeStorage(0) }.thenAnswer { throw SdkException.Generic("busy") }

        assertIs<SdkException.Generic>(sut.wipe(0).exceptionOrNull())

        assertEquals(SparkState(isSupported = true), sut.state.value)
        assertTrue(sut.depositClaims.value.isEmpty())
    }

    @Test
    fun `can send leaves room for spark's service fee`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(info(100_000uL))
        val sut = createSut()
        sut.start(0).getOrThrow()

        assertEquals(99_750uL, sut.state.value.sendableSats)
        assertTrue(sut.canSend(99_750uL))
        assertFalse(sut.canSend(100_000uL))
    }

    @Test
    fun `sendable amount fits the fee beside it`() {
        for (balance in listOf(0uL, 1uL, 400uL, 401uL, 9_572uL, 100_000uL, 21_000_000uL)) {
            val sendable = SparkFees.sendableSats(balance)
            assertTrue(sendable + SparkFees.serviceFeeSats(sendable) <= balance, "$balance")
            assertTrue(sendable + 1uL + SparkFees.serviceFeeSats(sendable + 1uL) > balance, "$balance leaves room")
        }
    }

    @Test
    fun `an uncertain spark send is not failed while its resync never reaches spark`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.thenAnswer { throw SdkException.Generic("timeout") }
        // The SDK reports a wallet sync that failed in its info, not by throwing.
        whenever { sparkService.getInfo() }.thenReturn(
            info(0uL).copy(lastSyncError = SyncError.Network("operators unreachable"), lastSyncedAt = 1uL)
        )
        val sut = createSut()
        val events = mutableListOf<Event>()
        backgroundScope.launch { sut.nodeEvents.collect { events += it } }
        runCurrent()

        assertIs<PaymentPendingException>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())
        advanceUntilIdle()

        assertTrue(events.none { it is Event.PaymentFailed }, "a send Spark may still finish was reported failed")
    }

    @Test
    fun `a first sync that fails leaves spark's payment list unread`() = test {
        whenever(sparkService.isSupported).thenReturn(true)
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(
            info(0uL).copy(lastSyncError = SyncError.Network("operators unreachable"))
        )
        val sut = createSut()

        sut.start(0).getOrThrow()

        assertFalse(sut.canListPayments(), "a restored wallet's unsynced list was read as complete")
    }

    @Test
    fun `a synced event after a failed wallet sync leaves spark's payment list unread`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(
            info(0uL).copy(lastSyncError = SyncError.Network("operators unreachable"))
        )
        val sut = createSut()
        sut.start(0).getOrThrow()
        runCurrent()

        // Spark also reports Synced for a partial sync, or one it skipped, while its wallet sync still fails.
        sparkEvents.emit(SdkEvent.Synced)
        runCurrent()

        assertFalse(sut.canListPayments(), "a list no wallet sync filled was read as complete")
    }

    @Test
    fun `a synced event before any wallet sync succeeded leaves spark's payment list unread`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        // After a connect the SDK reports no sync error until a sync ends, and no sync time until one succeeds.
        whenever { sparkService.getInfo() }.thenReturn(info(0uL).copy(lastSyncedAt = null))
        val sut = createSut()

        sut.start(0).getOrThrow()
        sparkEvents.emit(SdkEvent.Synced)
        runCurrent()

        assertFalse(sut.canListPayments(), "a list no wallet sync filled was read as complete")
    }

    @Test
    fun `an uncertain spark send keeps holding the node off while spark can't resync`() = test {
        whenever(sparkService.isSupported).thenReturn(true)
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.thenAnswer { throw SdkException.Generic("timeout") }
        val sut = createSut()
        sut.start(0).getOrThrow()
        // From here every resync fails, so Spark's list stays as it was before the send.
        whenever { sparkService.syncWallet() }.thenAnswer { throw SdkException.Generic("operators unreachable") }

        assertIs<PaymentPendingException>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())
        advanceUntilIdle()

        assertEquals(EarlierSend.Pending("hash"), sut.earlierLightningSend("lnbc1", "hash"))
    }

    @Test
    fun `a retry of an uncertain spark send is refused, not sent again`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.thenAnswer { throw SdkException.Generic("timeout") }
        val sut = createSut()
        sut.start(0).getOrThrow()
        whenever { sparkService.syncWallet() }.thenAnswer { throw SdkException.Generic("operators unreachable") }
        assertIs<PaymentPendingException>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())
        advanceUntilIdle()

        assertIs<PaymentPendingException>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())

        // Spark makes a new transfer for each send call, so a second one may pay the invoice again.
        verify(sparkService, times(1)).send(any())
    }

    @Test
    fun `a retry of an uncertain spark send is refused while spark has never synced`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        // No wallet sync has succeeded yet, so Spark's list can't be read.
        whenever { sparkService.getInfo() }.thenReturn(info(0uL).copy(lastSyncedAt = null))
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.thenAnswer { throw SdkException.Generic("timeout") }
        val sut = createSut()
        sut.start(0).getOrThrow()
        assertIs<PaymentPendingException>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())
        advanceUntilIdle()

        assertIs<PaymentPendingException>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())

        verify(sparkService, times(1)).send(any())
    }

    @Test
    fun `a retry of a spark send kept from before a restart is refused while its first sync fails`() = test {
        cacheData.value = AppCacheData(
            sparkSendHashes = listOf("hash"),
            sparkSendExpiries = mapOf("hash" to now.epochSeconds + 3_600),
        )
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(
            info(0uL).copy(lastSyncError = SyncError.Network("operators unreachable"))
        )
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.thenAnswer { throw SdkException.Generic("timeout") }
        val sut = createSut()
        sut.start(0).getOrThrow()

        assertIs<PaymentPendingException>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())

        // Spark makes a new transfer for each send call, so the retry would pay the invoice again.
        verify(sparkService, never()).send(any())
    }

    @Test
    fun `an unlisted spark send kept from before a restart reads as pending until its invoice expires`() = test {
        cacheData.value = AppCacheData(
            sparkSendHashes = listOf("hash", "expired"),
            sparkSendExpiries = mapOf("hash" to now.epochSeconds + 3_600, "expired" to now.epochSeconds - 1),
        )
        whenever(sparkService.isConnected).thenReturn(true)
        val sut = createSut()

        sut.start(0).getOrThrow()

        // Paykit reads a missing send as never made, so it would drop the proof of one Spark can still resume.
        assertEquals(SparkLightningSend.Found(PaymentStatus.PENDING, null), sut.lightningSend("hash"))
        assertEquals(SparkLightningSend.NotFound, sut.lightningSend("expired"))
    }

    @Test
    fun `a failed early claim is a failure the claim follower keeps going on`() = test {
        whenever { sparkService.claimDeposit("txid", 0u, 300uL) }
            .thenAnswer { throw SdkException.Generic("operators unreachable") }
        val choice = SparkClaimOffer.Choice("txid", 0u, earlyFeeSats = 300uL, 0u, 198uL, 3u)

        val result = runCatching { createSut().claimEarly(choice) }

        assertTrue(result.getOrThrow().isFailure, "a failed early claim threw instead of failing")
    }

    @Test
    fun `a spark send whose deadline passes before it goes out is not sent`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.thenReturn(sentPayment(fees = 0))
        val deadline = now + 1.seconds
        val sut = createSut()

        // The caller's last check before the send can wait past the deadline, as Paykit's does on its lock.
        val result = sut.payInvoice("lnbc1", 100uL, deadline, null) {
            now = deadline + 1.seconds
            true
        }

        assertIs<ServiceError.PaymentDeadlineExpired>(result.exceptionOrNull())
        verify(sparkService, never()).send(any())
    }

    @Test
    fun `an older failed row never ends an uncertain spark send while spark can't resync`() = test {
        whenever(sparkService.isSupported).thenReturn(true)
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.thenAnswer { throw SdkException.Generic("timeout") }
        val sut = createSut()
        sut.start(0).getOrThrow()
        val events = mutableListOf<Event>()
        backgroundScope.launch { sut.nodeEvents.collect { events += it } }
        runCurrent()
        // An earlier attempt of the invoice failed, and from here every wallet sync fails without throwing.
        whenever { sparkService.listPayments() }
            .thenReturn(listOf(sentPayment(fees = 0).copy(status = PaymentStatus.FAILED)))
        whenever { sparkService.getInfo() }.thenReturn(
            info(0uL).copy(lastSyncError = SyncError.Network("operators unreachable"), lastSyncedAt = 1uL)
        )

        assertIs<PaymentPendingException>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())
        advanceUntilIdle()

        assertTrue(events.none { it is Event.PaymentFailed }, "an older failure ended a send Spark may finish")
        assertEquals(EarlierSend.Pending("hash"), sut.earlierLightningSend("lnbc1", "hash"))
    }

    @Test
    fun `an uncertain spark retry stays held after a restart while spark lists only its older failure`() = test {
        assertUncertainRetryHeldAfterRestart(syncError = null)
    }

    @Test
    fun `an uncertain spark retry stays held after a restart whose first sync fails`() = test {
        assertUncertainRetryHeldAfterRestart(syncError = SyncError.Network("operators unreachable"))
    }

    private suspend fun TestScope.assertUncertainRetryHeldAfterRestart(syncError: SyncError?) {
        val store = CacheStore(InMemoryDataStore(AppCacheData()), clock)
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.thenAnswer { throw SdkException.Generic("timeout") }
        // An earlier attempt of the invoice failed, and the retry's outcome is unknown when the app stops.
        whenever { sparkService.listPayments() }
            .thenReturn(listOf(sentPayment(fees = 0).copy(status = PaymentStatus.FAILED)))
        whenever { sparkService.getInfo() }.thenReturn(
            info(0uL).copy(lastSyncError = SyncError.Network("operators unreachable"))
        )
        val before = createSut(store)
        before.start(0).getOrThrow()
        assertIs<PaymentPendingException>(before.payInvoice("lnbc1", 100uL).exceptionOrNull())
        runCurrent()

        whenever { sparkService.getInfo() }.thenReturn(
            info(0uL).copy(lastSyncError = syncError, lastSyncedAt = now.epochSeconds.toULong() + 1uL)
        )
        val after = createSut(store)
        after.start(0).getOrThrow()

        assertTrue("hash" in store.data.first().sparkSendHashes, "A restart released a retry Spark may resume")
        assertEquals(SparkLightningSend.Found(PaymentStatus.PENDING, null), after.lightningSend("hash"))
        assertEquals(EarlierSend.Pending("hash"), after.earlierLightningSend("lnbc1", "hash"))
    }

    @Test
    fun `a spark retry cut off by process death stays held after a restart while spark lists only its older failure`() =
        test {
            val store = CacheStore(InMemoryDataStore(AppCacheData()), clock)
            whenever(sparkService.isConnected).thenReturn(true)
            val prepared = unexpiredPrepared()
            whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
            // The process dies inside Spark's send call, so the call never returns.
            whenever { sparkService.send(any()) }.doSuspendableAnswer { awaitCancellation() }
            whenever { sparkService.listPayments() }
                .thenReturn(listOf(sentPayment(fees = 0).copy(status = PaymentStatus.FAILED)))
            val before = createSut(store)
            before.start(0).getOrThrow()
            backgroundScope.launch { before.payInvoice("lnbc1", 100uL) }
            runCurrent()

            whenever { sparkService.getInfo() }.thenReturn(
                info(0uL).copy(lastSyncedAt = now.epochSeconds.toULong() + 1uL)
            )
            val after = createSut(store)
            after.start(0).getOrThrow()

            assertEquals(
                EarlierSend.Pending("hash"),
                after.earlierLightningSend("lnbc1", "hash"),
                "A restart released a retry Spark may resume to the node",
            )
            assertEquals(SparkLightningSend.Found(PaymentStatus.PENDING, null), after.lightningSend("hash"))
        }

    @Test
    fun `a cancelled spark retry stays held while spark lists only its older failure`() = test {
        val store = CacheStore(InMemoryDataStore(AppCacheData()), clock)
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = unexpiredPrepared()
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.doSuspendableAnswer { awaitCancellation() }
        whenever { sparkService.listPayments() }
            .thenReturn(listOf(sentPayment(fees = 0).copy(status = PaymentStatus.FAILED)))
        val sut = createSut(store)
        sut.start(0).getOrThrow()
        val sending = launch { sut.payInvoice("lnbc1", 100uL) }
        runCurrent()

        sending.cancel()
        runCurrent()

        assertEquals(
            EarlierSend.Pending("hash"),
            sut.earlierLightningSend("lnbc1", "hash"),
            "A cancelled send released a retry Spark may resume to the node",
        )
        assertEquals(SparkLightningSend.Found(PaymentStatus.PENDING, null), sut.lightningSend("hash"))
    }

    @Test
    fun `an uncertain spark retry past an older failure reads as pending to paykit and quickpay`() = test {
        val store = CacheStore(InMemoryDataStore(AppCacheData()), clock)
        store.addSparkSendHash("hash", expiresAt = now.epochSeconds + 3_600)
        store.addSparkUncertainSend("hash", now.epochSeconds)
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.listPayments() }
            .thenReturn(listOf(sentPayment(fees = 0).copy(status = PaymentStatus.FAILED)))
        val sut = createSut(store)
        sut.start(0).getOrThrow()

        assertEquals(SparkLightningSend.Found(PaymentStatus.PENDING, null), sut.lightningSend("hash"))
        assertEquals(
            PaymentStatus.PENDING,
            sut.lightningSendStatuses()?.get("hash"),
            "QuickPay reads an uncertain retry as failed",
        )
        assertTrue(
            "hash" in sut.attemptedLightningPaymentHashes().orEmpty(),
            "Paykit reads an uncertain retry as never attempted",
        )
    }

    private fun unexpiredPrepared(): PrepareSendPaymentResponse {
        val details = mock<Bolt11InvoiceDetails> {
            on { paymentHash } doReturn "hash"
            on { timestamp }.thenAnswer { now.epochSeconds }
            on { expiry }.thenAnswer { 3_600L }
        }
        return prepared(feeSats = 0uL).copy(paymentMethod = SendPaymentMethod.Bolt11Invoice(details, null, 0uL))
    }

    @Test
    fun `an uncertain spark send is settled when spark syncs after its polling stopped`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.thenAnswer { throw SdkException.Generic("timeout") }
        whenever { sparkService.getInfo() }.thenReturn(
            info(0uL).copy(lastSyncError = SyncError.Network("operators unreachable"))
        )
        val sut = createSut()
        sut.start(0).getOrThrow()
        val events = mutableListOf<Event>()
        backgroundScope.launch { sut.nodeEvents.collect { events += it } }
        runCurrent()
        assertIs<PaymentPendingException>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())
        advanceUntilIdle()

        // Back online after every poll gave up, Spark syncs and holds no such send.
        whenever { sparkService.getInfo() }.thenReturn(
            info(0uL).copy(lastSyncedAt = now.epochSeconds.toULong() + 1uL)
        )
        sparkEvents.emit(SdkEvent.Synced)
        advanceUntilIdle()

        assertEquals("hash", assertIs<Event.PaymentFailed>(events.single()).paymentHash)
    }

    @Test
    fun `a spark send that fails after its polling stopped no longer holds the node off`() = test {
        whenever(sparkService.isSupported).thenReturn(true)
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.thenAnswer { throw SdkException.Generic("timeout") }
        val sut = createSut()
        sut.start(0).getOrThrow()
        whenever { sparkService.getInfo() }.thenReturn(
            info(0uL).copy(lastSyncError = SyncError.Network("operators unreachable"))
        )
        assertIs<PaymentPendingException>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())
        advanceUntilIdle()

        // Spark resumes the send later and it fails.
        val failed = sentPayment(fees = 0).copy(status = PaymentStatus.FAILED)
        whenever { sparkService.listPayments() }.thenReturn(listOf(failed))
        sparkEvents.emit(SdkEvent.PaymentFailed(failed))
        advanceUntilIdle()

        assertNull(sut.earlierLightningSend("lnbc1", "hash"), "a failed send still held the node's retry")
    }

    @Test
    fun `a spark send missing after a sync that reached spark stays pending until its invoice expires`() = test {
        whenever(sparkService.isSupported).thenReturn(true)
        whenever(sparkService.isConnected).thenReturn(true)
        val details = mock<Bolt11InvoiceDetails> {
            on { paymentHash } doReturn "hash"
            on { timestamp }.thenAnswer { now.epochSeconds }
            on { expiry }.thenAnswer { 3_600L }
        }
        val unexpired = prepared(feeSats = 0uL)
            .copy(paymentMethod = SendPaymentMethod.Bolt11Invoice(details, null, 0uL))
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(unexpired)
        whenever { sparkService.send(any()) }.thenAnswer { throw SdkException.Generic("timeout") }
        val sut = createSut()
        sut.start(0).getOrThrow()
        val events = mutableListOf<Event>()
        backgroundScope.launch { sut.nodeEvents.collect { events += it } }
        runCurrent()
        // Every sync after the failure reaches Spark, yet Spark lists no such send: resuming it may have failed.
        whenever { sparkService.getInfo() }.thenReturn(
            info(0uL).copy(lastSyncedAt = now.epochSeconds.toULong() + 1uL)
        )

        assertIs<PaymentPendingException>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())
        advanceUntilIdle()
        sparkEvents.emit(SdkEvent.Synced)
        advanceUntilIdle()

        assertTrue(events.none { it is Event.PaymentFailed }, "a send Spark may still finish was reported failed")
        assertEquals(EarlierSend.Pending("hash"), sut.earlierLightningSend("lnbc1", "hash"))
    }

    private fun info(balanceSats: ULong) = GetInfoResponse(
        identityPubkey = "pubkey",
        balanceSats = balanceSats,
        tokenBalances = emptyMap(),
        recoverableFundsSats = 0uL,
        lastSyncError = null,
        lastSyncedAt = 1uL,
    )

    private fun prepared(feeSats: ULong): PrepareSendPaymentResponse {
        val details = mock<Bolt11InvoiceDetails> { on { paymentHash } doReturn "hash" }
        return PrepareSendPaymentResponse(
            paymentMethod = SendPaymentMethod.Bolt11Invoice(details, null, feeSats),
            amount = BigInteger.valueOf(100),
            tokenIdentifier = null,
            conversionEstimate = null,
            feePolicy = FeePolicy.FEES_EXCLUDED,
        )
    }

    private fun withdrawal(expiresAt: ULong, amountSats: Long = 10_000): PrepareSendPaymentResponse {
        val speed = SendOnchainSpeedFeeQuote(userFeeSat = 300uL, l1BroadcastFeeSat = 200uL)
        val quote = SendOnchainFeeQuote("quote", expiresAt, speed, speed, speed, isEstimate = false)
        return PrepareSendPaymentResponse(
            paymentMethod = SendPaymentMethod.BitcoinAddress(mock<BitcoinAddressDetails>(), quote),
            amount = BigInteger.valueOf(amountSats),
            tokenIdentifier = null,
            conversionEstimate = null,
            feePolicy = FeePolicy.FEES_INCLUDED,
        )
    }

    private fun receivedPayment() = sentPayment(fees = 0).copy(id = "receive", paymentType = PaymentType.RECEIVE)

    private fun listDeposit() {
        whenever { sparkService.listUnclaimedDeposits() }.thenReturn(
            listOf(DepositInfo("txid", 0u, 50_000uL, false, null, null, null, null, null, null))
        )
    }

    private fun quote(earlyFee: ULong?) = FetchClaimDepositQuoteResponse(
        amountSats = 50_000uL,
        confirmations = 0u,
        instant = earlyFee?.let { ClaimDepositQuote(0u, 50_000uL - it, it, 200uL, false) },
        mature = ClaimDepositQuote(3u, 49_802uL, 198uL, null, true),
    )

    private fun depositPayment() = sentPayment(fees = 300).copy(
        id = "deposit",
        paymentType = PaymentType.RECEIVE,
        method = PaymentMethod.DEPOSIT,
        details = PaymentDetails.Deposit(txId = "txid", vout = 0u),
    )

    private fun Payment.withHash(hash: String): Payment {
        val lightning = details as PaymentDetails.Lightning
        return copy(details = lightning.copy(htlcDetails = lightning.htlcDetails.copy(paymentHash = hash)))
    }

    private fun sentPayment(fees: Int) = Payment(
        id = "send",
        paymentType = PaymentType.SEND,
        status = PaymentStatus.COMPLETED,
        amount = BigInteger.valueOf(128),
        fees = BigInteger.valueOf(fees.toLong()),
        timestamp = 1uL,
        method = PaymentMethod.LIGHTNING,
        details = PaymentDetails.Lightning(
            description = null,
            invoice = "lnbc1280n1send",
            destinationPubkey = "pubkey",
            htlcDetails = SparkHtlcDetails("hash", "preimage", 1uL, SparkHtlcStatus.PREIMAGE_SHARED),
            lnurlPayInfo = null,
            lnurlWithdrawInfo = null,
            lnurlReceiveMetadata = null,
            conversionInfo = null,
        ),
        conversionDetails = null,
    )
}
