package to.bitkit.repositories

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.services.LightningService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(kotlin.time.ExperimentalTime::class)
class OnchainSendCoordinatorTest : BaseUnitTest() {
    private val input = OnchainSendInput("11".repeat(32), 0u)
    private val firstTxid = "ab".repeat(32)
    private val nextTxid = "cd".repeat(32)
    private val cancelTxid = "ef".repeat(32)

    private inner class Fixture {
        var saved: String? = null
        var failWrite = false
        var walletIndex = 0
        val keychain = mock<Keychain>()
        val service = mock<LightningService>()
        val store: OnchainSendAttemptStore

        init {
            whenever(service.currentWalletIndex).thenAnswer { walletIndex }
            whenever(keychain.loadString(eq(Keychain.Key.ONCHAIN_SEND_ATTEMPT.name), any())).thenAnswer { saved }
            whenever { keychain.upsertString(eq(Keychain.Key.ONCHAIN_SEND_ATTEMPT.name), any(), any()) }
                .doSuspendableAnswer {
                    if (failWrite) error("storage unavailable")
                    saved = it.getArgument(1)
                }
            store = OnchainSendAttemptStore(testDispatcher, keychain, service, kotlin.time.Clock.System)
        }

        suspend fun admit(isMax: Boolean = false): OnchainSendAttempt = store.admit(
            walletId = "wallet-1",
            requestId = if (isMax) {
                null
            } else {
                PaykitPaymentRequestId(
                    "request",
                    "payer",
                    "receiver"
                )
            },
            orderId = "order-1", address = "bcrt1qrecipient", amountSats = 1_000uL,
            isMaxAmount = isMax, feeRateSatsPerVByte = 1uL, isTransfer = true,
            channelId = "channel-1", tags = listOf("tag"),
            transferContext = OnchainTransferContext(1_100uL, 2_000uL, 900uL, 1_000uL), beforeSendAttempt = {},
            payerIdentity = "original-payer",
        )

        fun receipt(txid: String = firstTxid, amount: ULong = 1_000uL) =
            OnchainPreparedReceipt(txid, listOf(input), "bcrt1qrecipient", amount, miningFeeSats = 50uL)

        suspend fun unresolved(): OnchainSendAttempt {
            val attempt = admit()
            store.retainPreparedReceipt(attempt.attemptId, 0, receipt(), false)
            return store.recordOutcome(attempt.attemptId, OnchainSendOutcome.Unknown(firstTxid), 0)
        }

        suspend fun dispatchedUnknown(): OnchainSendAttempt {
            val attempt = admit()
            store.retainPreparedReceipt(attempt.attemptId, 0, receipt(), false)
            val outcome = store.broadcastPreparedCandidate(attempt.attemptId, 0, firstTxid) { beforeDispatch ->
                beforeDispatch()
                OnchainSendOutcome.Unknown(firstTxid)
            }
            return store.recordOutcome(attempt.attemptId, outcome, 0)
        }
    }

    @Test
    fun `retry fee approval uses the exact prepared object before retention and dispatch`() = test {
        val f = Fixture()
        val original = f.unresolved()
        var preparations = 0
        var broadcasts = 0
        val receipt = f.receipt(nextTxid)
        val sender = object : OnchainPreparedSender {
            override suspend fun prepareInitial(attempt: OnchainSendAttempt): PreparedOnchainSend = error("unused")
            override suspend fun prepareRecovery(
                attempt: OnchainSendAttempt,
                feeRateSatsPerVByte: ULong,
                paymentDeadlineAt: Instant?
            ): PreparedOnchainSend {
                preparations++
                return PreparedOnchainSend(receipt) {
                    it()
                    broadcasts++
                    OnchainSendOutcome.Unknown(nextTxid)
                }
            }
        }
        val coordinator = OnchainSendCoordinator(f.store, sender, testDispatcher)
        var approved = false
        coordinator.retryOriginal(
            original.attemptId,
            original.walletId,
            4uL,
            approvePrepared = { preview ->
                assertEquals(receipt.copy(feeRateSatsPerVByte = 4uL), preview)
                assertEquals(listOf(firstTxid), f.store.current()?.candidateTxids)
                assertEquals(0, broadcasts)
                approved = true
            }
        ) { assertTrue(approved) }.getOrThrow()
        assertEquals(1, preparations)
        assertEquals(1, broadcasts)
    }

    @Test
    fun `accepted retry without durable acceptance stays pending after restart`() = test {
        val f = Fixture()
        val original = f.unresolved()
        val sender = object : OnchainPreparedSender {
            override suspend fun prepareInitial(attempt: OnchainSendAttempt): PreparedOnchainSend = error("unused")
            override suspend fun prepareRecovery(
                attempt: OnchainSendAttempt,
                feeRateSatsPerVByte: ULong,
                paymentDeadlineAt: Instant?,
            ) = PreparedOnchainSend(f.receipt(nextTxid)) {
                it()
                f.failWrite = true
                OnchainSendOutcome.Accepted(nextTxid)
            }
        }
        val result = OnchainSendCoordinator(f.store, sender, testDispatcher)
            .retryOriginal(original.attemptId, original.walletId, 2uL) {}
        assertTrue(result.exceptionOrNull() is OnchainSendPendingError)
        val restarted = OnchainSendAttemptStore(testDispatcher, f.keychain, f.service, kotlin.time.Clock.System)
        assertTrue(restarted.current()?.blocksNextSend == true)
        assertTrue(restarted.current()?.hasPositiveEvidence == false)
        assertEquals(listOf(firstTxid, nextTxid), restarted.current()?.candidateTxids)
    }

    @Test
    fun `retry retains deadline through authorization and rejects expired queued broadcast`() = test {
        for (expired in listOf(false, true)) {
            val f = Fixture()
            val original = f.unresolved()
            val deadline = Instant.fromEpochSeconds(1_800_000_000)
            var dispatchTime = deadline
            var broadcasts = 0
            val sender = object : OnchainPreparedSender {
                override suspend fun prepareInitial(attempt: OnchainSendAttempt): PreparedOnchainSend = error("unused")
                override suspend fun prepareRecovery(
                    attempt: OnchainSendAttempt,
                    feeRateSatsPerVByte: ULong,
                    paymentDeadlineAt: Instant?,
                ) = PreparedOnchainSend(f.receipt(nextTxid)) {
                    if (paymentDeadlineAt != null && dispatchTime > paymentDeadlineAt) {
                        throw PaykitPaymentRequestError.RequestExpired
                    }
                    it()
                    broadcasts++
                    OnchainSendOutcome.Unknown(nextTxid)
                }
            }
            val result = OnchainSendCoordinator(f.store, sender, testDispatcher).retryOriginal(
                original.attemptId,
                original.walletId,
                2uL,
                deadline,
            ) { dispatchTime = if (expired) deadline + 1.milliseconds else deadline }
            assertEquals(if (expired) 0 else 1, broadcasts, "Expired retry must not broadcast")
            assertEquals(expired, result.isFailure)
            val retained = requireNotNull(f.store.current())
            assertEquals(original.attemptId, retained.attemptId)
            assertEquals(original.amountSats, retained.amountSats)
            assertEquals(original.originalInputs, retained.originalInputs)
            assertTrue(retained.blocksNextSend)
            assertEquals(!expired, nextTxid in retained.candidateTxids)
            if (expired) {
                assertEquals(original.candidateTxids, retained.candidateTxids)
                assertEquals(original.candidateMiningFees, retained.candidateMiningFees)
                assertEquals(original.txid, retained.txid)
                assertEquals(original.evidence, retained.evidence)
            }
        }
    }

    @Test
    fun `receipt is durable before first broadcast and restart retains candidate`() = test {
        val f = Fixture()
        val attempt = f.admit()
        var sends = 0
        val sender = object : OnchainPreparedSender {
            override suspend fun prepareInitial(attempt: OnchainSendAttempt) = PreparedOnchainSend(f.receipt()) {
                it()
                assertTrue(requireNotNull(f.saved).contains(firstTxid))
                val durable = Json.decodeFromString<OnchainSendAttempt>(
                    requireNotNull(f.saved),
                )
                assertEquals(mapOf(firstTxid to 50uL), durable.candidateMiningFees)
                sends++
                OnchainSendOutcome.Unknown(firstTxid)
            }
            override suspend fun prepareRecovery(
                attempt: OnchainSendAttempt,
                feeRateSatsPerVByte: ULong,
                paymentDeadlineAt: Instant?,
            ): PreparedOnchainSend =
                error("not requested")
        }
        assertTrue(OnchainSendCoordinator(f.store, sender, testDispatcher).sendInitial(attempt).isSuccess)
        val reopened = OnchainSendAttemptStore(testDispatcher, f.keychain, f.service, kotlin.time.Clock.System)
        assertEquals(listOf(firstTxid), reopened.current()?.candidateTxids)
        assertEquals(listOf(input), reopened.current()?.originalInputs)
        assertEquals(mapOf(firstTxid to 50uL), reopened.current()?.candidateMiningFees)
        assertTrue(reopened.current()?.isUnresolved == true)
        assertEquals(1, sends)
    }

    @Test
    fun `receipt storage failure prevents broadcast`() = test {
        val f = Fixture()
        val attempt = f.admit()
        var sends = 0
        f.failWrite = true
        val sender = object : OnchainPreparedSender {
            override suspend fun prepareInitial(attempt: OnchainSendAttempt) = PreparedOnchainSend(f.receipt()) {
                it()
                sends++
                OnchainSendOutcome.Accepted(firstTxid)
            }
            override suspend fun prepareRecovery(
                attempt: OnchainSendAttempt,
                feeRateSatsPerVByte: ULong,
                paymentDeadlineAt: Instant?,
            ): PreparedOnchainSend =
                error("not requested")
        }
        assertTrue(OnchainSendCoordinator(f.store, sender, testDispatcher).sendInitial(attempt).isFailure)
        assertEquals(0, sends)
        assertTrue(f.store.current()?.blocksNextSend == true)
        assertTrue(f.store.current()?.candidateMiningFees.isNullOrEmpty())
    }

    @Test
    fun `initial Max actual amount becomes fixed recovery amount with original context`() = test {
        val f = Fixture()
        val attempt = f.admit(isMax = true)
        var authorizations = 0
        val sender = object : OnchainPreparedSender {
            override suspend fun prepareInitial(attempt: OnchainSendAttempt) = PreparedOnchainSend(
                f.receipt(amount = 1_050uL)
            ) {
                it()
                OnchainSendOutcome.Unknown(firstTxid)
            }
            override suspend fun prepareRecovery(
                attempt: OnchainSendAttempt,
                feeRateSatsPerVByte: ULong,
                paymentDeadlineAt: Instant?,
            ): PreparedOnchainSend {
                assertEquals(1_050uL, attempt.amountSats)
                assertEquals(listOf(input), attempt.originalInputs)
                assertEquals("order-1", attempt.orderId)
                assertEquals("wallet-1", attempt.walletId)
                assertEquals(null, attempt.requestId)
                assertEquals("channel-1", attempt.channelId)
                assertEquals(OnchainTransferContext(1_100uL, 2_000uL, 900uL, 1_000uL), attempt.transferContext)
                return PreparedOnchainSend(f.receipt(nextTxid, 1_050uL)) {
                    it()
                    OnchainSendOutcome.Unknown(nextTxid)
                }
            }
        }
        val coordinator = OnchainSendCoordinator(f.store, sender, testDispatcher)
        coordinator.sendInitial(attempt).getOrThrow()
        coordinator.retryOriginal(attempt.attemptId, attempt.walletId, 2uL) { authorizations++ }.getOrThrow()
        assertEquals(listOf(firstTxid, nextTxid), f.store.current()?.candidateTxids)
        assertEquals(1, authorizations)
    }

    @Test
    fun `recovery rejects changed inputs amount or wallet before broadcasting`() = test {
        for (mode in listOf("input", "amount", "wallet")) {
            val f = Fixture()
            val attempt = f.unresolved()
            var sends = 0
            val sender = object : OnchainPreparedSender {
                override suspend fun prepareInitial(attempt: OnchainSendAttempt): PreparedOnchainSend = error(
                    "not requested",
                )
                override suspend fun prepareRecovery(
                    attempt: OnchainSendAttempt,
                    feeRateSatsPerVByte: ULong,
                    paymentDeadlineAt: Instant?,
                ): PreparedOnchainSend {
                    if (mode == "wallet") f.walletIndex = 1
                    val receipt = when (mode) {
                        "input" -> f.receipt(nextTxid).copy(inputs = listOf(OnchainSendInput("22".repeat(32), 1u)))
                        "amount" -> f.receipt(nextTxid, 999uL)
                        else -> f.receipt(nextTxid)
                    }
                    return PreparedOnchainSend(receipt) {
                        it()
                        sends++
                        OnchainSendOutcome.Accepted(nextTxid)
                    }
                }
            }
            val result = OnchainSendCoordinator(
                f.store,
                sender,
                testDispatcher
            ).retryOriginal(attempt.attemptId, attempt.walletId, 2uL) {}
            assertTrue(result.isFailure)
            assertEquals(0, sends)
        }
    }

    @Test
    fun `older candidate observation during successor broadcast wins over Unknown`() = test {
        val f = Fixture()
        val attempt = f.unresolved()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val sender = object : OnchainPreparedSender {
            override suspend fun prepareInitial(attempt: OnchainSendAttempt): PreparedOnchainSend = error(
                "not requested",
            )
            override suspend fun prepareRecovery(
                attempt: OnchainSendAttempt,
                feeRateSatsPerVByte: ULong,
                paymentDeadlineAt: Instant?
            ) =
                PreparedOnchainSend(f.receipt(nextTxid)) {
                    it()
                    entered.complete(Unit)
                    finish.await()
                    OnchainSendOutcome.Unknown(nextTxid)
                }
        }
        val coordinator = OnchainSendCoordinator(f.store, sender, testDispatcher)
        val retry = async { coordinator.retryOriginal(attempt.attemptId, attempt.walletId, 2uL) {} }
        entered.await()
        val observed = async { f.store.observeExactTransaction(firstTxid) }
        finish.complete(Unit)
        retry.await().getOrThrow()
        observed.await()
        assertEquals(firstTxid, f.store.current()?.txid)
        assertEquals(OnchainSendEvidence.Observed, f.store.current()?.evidence)
        assertFailsWith<OnchainSendBlockedError> { f.admit() }
    }

    @Test
    fun `positive observation during preparation prevents another broadcast`() = test {
        val f = Fixture()
        val attempt = f.unresolved()
        var sends = 0
        val sender = object : OnchainPreparedSender {
            override suspend fun prepareInitial(attempt: OnchainSendAttempt): PreparedOnchainSend = error(
                "not requested",
            )
            override suspend fun prepareRecovery(
                attempt: OnchainSendAttempt,
                feeRateSatsPerVByte: ULong,
                paymentDeadlineAt: Instant?,
            ): PreparedOnchainSend {
                f.store.observeExactTransaction(firstTxid)
                return PreparedOnchainSend(f.receipt(nextTxid)) {
                    it()
                    sends++
                    OnchainSendOutcome.Accepted(nextTxid)
                }
            }
        }
        assertTrue(
            OnchainSendCoordinator(
                f.store,
                sender,
                testDispatcher
            ).retryOriginal(attempt.attemptId, attempt.walletId, 2uL) {}.getOrThrow() ==
                OnchainSendOutcome.Accepted(firstTxid)
        )
        assertEquals(0, sends)
        assertEquals(firstTxid, f.store.current()?.txid)
    }

    @Test
    fun `preexisting readable guard without receipt cannot retry`() = test {
        val f = Fixture()
        val attempt = f.admit()
        f.store.recordOutcome(attempt.attemptId, OnchainSendOutcome.Unknown(firstTxid), 0)
        val sender = mock<OnchainPreparedSender>()
        assertTrue(
            OnchainSendCoordinator(
                f.store,
                sender,
                testDispatcher
            ).retryOriginal(attempt.attemptId, attempt.walletId, 2uL) {}.isFailure
        )
        assertEquals(OnchainSendEvidence.Unknown, f.store.current()?.evidence)
    }

    @Test
    fun `adapter uses native Max only initially and exact fixed inputs for every recovery`() = test {
        val f = Fixture()
        val initial = f.admit(isMax = true)
        val native = mock<OnchainPreparationProtocol>()
        whenever(native.prepareMax(initial.address, true, 1uL, 0)).thenReturn(
            PreparedOnchainSend(f.receipt(amount = 1_050uL)) {
                it()
                OnchainSendOutcome.Unknown(firstTxid)
            },
        )
        whenever(native.prepareFixed(initial.address, 1_050uL, 2uL, listOf(input), 0)).thenReturn(
            PreparedOnchainSend(f.receipt(nextTxid, 1_050uL)) {
                it()
                OnchainSendOutcome.Unknown(nextTxid)
            },
        )
        val coordinator = OnchainSendCoordinator(f.store, OnchainPreparedSenderAdapter(native), testDispatcher)
        coordinator.sendInitial(initial).getOrThrow()
        coordinator.retryOriginal(initial.attemptId, initial.walletId, 2uL) {}.getOrThrow()
        verify(native).prepareMax(initial.address, true, 1uL, 0)
        verify(native).prepareFixed(initial.address, 1_050uL, 2uL, listOf(input), 0)
    }

    @Test
    fun `original authorization failure drops only unsent retry candidate`() = test {
        val f = Fixture()
        val unknown = f.unresolved()
        val attempt = f.store.recordOutcome(
            unknown.attemptId,
            OnchainSendOutcome.Rejected(firstTxid, "mempool min fee not met"),
            0,
        )
        var prepares = 0
        var broadcasts = 0
        val sender = object : OnchainPreparedSender {
            override suspend fun prepareInitial(attempt: OnchainSendAttempt): PreparedOnchainSend = error(
                "not requested",
            )
            override suspend fun prepareRecovery(
                attempt: OnchainSendAttempt,
                feeRateSatsPerVByte: ULong,
                paymentDeadlineAt: Instant?,
            ): PreparedOnchainSend {
                prepares++
                return PreparedOnchainSend(f.receipt(nextTxid)) {
                    it()
                    broadcasts++
                    OnchainSendOutcome.Accepted(nextTxid)
                }
            }
        }
        val result = OnchainSendCoordinator(
            f.store,
            sender,
            testDispatcher
        ).retryOriginal(attempt.attemptId, attempt.walletId, 2uL) { retained ->
            assertEquals(f.store.current(), retained)
            error("original order unavailable")
        }
        assertTrue(result.isFailure)
        assertEquals(1, prepares)
        assertEquals(0, broadcasts)
        assertEquals(listOf(firstTxid), f.store.current()?.candidateTxids)
        assertEquals(firstTxid, f.store.current()?.txid)
        assertEquals(OnchainSendEvidence.Rejected, f.store.current()?.evidence)
        assertEquals("mempool min fee not met", f.store.current()?.refusalReason)
    }

    @Test
    fun `payer switch during suspended preparation denies dispatch and retains candidate guard`() = test {
        val f = Fixture()
        val original = f.unresolved()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var currentPayer = original.payerIdentity
        var broadcasts = 0
        var authorizations = 0
        var authorizedRecord: OnchainSendAttempt? = null
        val sender = object : OnchainPreparedSender {
            override suspend fun prepareInitial(attempt: OnchainSendAttempt): PreparedOnchainSend = error("unused")
            override suspend fun prepareRecovery(
                attempt: OnchainSendAttempt,
                feeRateSatsPerVByte: ULong,
                paymentDeadlineAt: Instant?,
            ): PreparedOnchainSend {
                entered.complete(Unit)
                finish.await()
                return PreparedOnchainSend(f.receipt(nextTxid)) {
                    it()
                    broadcasts++
                    OnchainSendOutcome.Accepted(nextTxid)
                }
            }
        }
        val coordinator = OnchainSendCoordinator(f.store, sender, testDispatcher)
        val result = async {
            coordinator.retryOriginal(original.attemptId, original.walletId, 2uL) { retained ->
                authorizations++
                authorizedRecord = retained
                check(currentPayer == original.payerIdentity) { "original payer changed" }
            }
        }
        entered.await()
        currentPayer = "different-payer"
        finish.complete(Unit)
        assertTrue(result.await().isFailure)
        assertEquals(0, broadcasts)
        assertEquals(1, authorizations)
        val retained = f.store.current()
        assertEquals(retained?.attemptId, authorizedRecord?.attemptId)
        assertEquals(listOf(firstTxid, nextTxid), authorizedRecord?.candidateTxids)
        assertEquals(listOf(firstTxid), retained?.candidateTxids)
        assertEquals(original.payerIdentity, retained?.payerIdentity)
        assertEquals(original.requestId, retained?.requestId)
        assertEquals(original.orderId, retained?.orderId)
        assertEquals(original.originalInputs, retained?.originalInputs)
        assertEquals(original.evidence, retained?.evidence)
        val restarted = OnchainSendAttemptStore(testDispatcher, f.keychain, f.service, kotlin.time.Clock.System)
        assertEquals(retained, restarted.current())
        assertFailsWith<OnchainSendBlockedError> { f.admit() }
    }

    @Test
    fun `concurrent explicit retries never prepare or broadcast in parallel`() = test {
        val f = Fixture()
        val attempt = f.unresolved()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var prepares = 0
        var broadcasts = 0
        var active = 0
        val sender = object : OnchainPreparedSender {
            override suspend fun prepareInitial(attempt: OnchainSendAttempt): PreparedOnchainSend = error(
                "not requested",
            )
            override suspend fun prepareRecovery(
                attempt: OnchainSendAttempt,
                feeRateSatsPerVByte: ULong,
                paymentDeadlineAt: Instant?,
            ): PreparedOnchainSend {
                assertEquals(0, active)
                prepares++
                val txid = if (prepares == 1) nextTxid else "ef".repeat(32)
                return PreparedOnchainSend(f.receipt(txid)) {
                    it()
                    active++
                    assertEquals(1, active)
                    broadcasts++
                    if (broadcasts == 1) {
                        entered.complete(Unit)
                        finish.await()
                    }
                    active--
                    OnchainSendOutcome.Unknown(txid)
                }
            }
        }
        val coordinator = OnchainSendCoordinator(f.store, sender, testDispatcher)
        val first = async { coordinator.retryOriginal(attempt.attemptId, attempt.walletId, 2uL) {} }
        entered.await()
        val second = async { coordinator.retryOriginal(attempt.attemptId, attempt.walletId, 3uL) {} }
        assertEquals(1, prepares)
        finish.complete(Unit)
        first.await().getOrThrow()
        second.await().getOrThrow()
        assertEquals(2, broadcasts)
        assertEquals(listOf(firstTxid, nextTxid, "ef".repeat(32)), f.store.current()?.candidateTxids)
    }

    @Test
    fun `known older positive survives persistence failure and late successor outcome`() = test {
        val f = Fixture()
        val attempt = f.unresolved()
        f.store.retainPreparedReceipt(attempt.attemptId, 0, f.receipt(nextTxid), true)
        f.failWrite = true
        assertFailsWith<IllegalStateException> { f.store.observeExactTransaction(firstTxid) }
        val retained = f.store.recordOutcome(attempt.attemptId, OnchainSendOutcome.Unknown(nextTxid), 0)
        assertEquals(firstTxid, retained.txid)
        assertEquals(OnchainSendEvidence.Observed, retained.evidence)
        f.failWrite = false
        f.store.markLocalFollowupComplete(attempt.attemptId, 0)
        val reopened = OnchainSendAttemptStore(testDispatcher, f.keychain, f.service, kotlin.time.Clock.System)
        assertEquals(firstTxid, reopened.current()?.txid)
        assertTrue(reopened.current()?.localFollowupComplete == true)
    }

    @Test
    fun `old positive write failure during successor preparation returns original winner without sending`() = test {
        val f = Fixture()
        val original = f.unresolved()
        var sends = 0
        val sender = object : OnchainPreparedSender {
            override suspend fun prepareInitial(attempt: OnchainSendAttempt): PreparedOnchainSend = error("unused")
            override suspend fun prepareRecovery(
                attempt: OnchainSendAttempt,
                feeRateSatsPerVByte: ULong,
                paymentDeadlineAt: Instant?
            ): PreparedOnchainSend {
                f.failWrite = true
                runCatching { f.store.observeExactTransaction(firstTxid) }
                return PreparedOnchainSend(f.receipt(nextTxid)) {
                    it()
                    sends++
                    error("must not broadcast after positive observation")
                }
            }
        }
        val result = OnchainSendCoordinator(f.store, sender, testDispatcher)
            .retryOriginal(original.attemptId, original.walletId, 2uL) {}
        assertEquals(OnchainSendOutcome.Accepted(firstTxid), result.getOrThrow())
        assertEquals(0, sends)
        assertEquals(OnchainSendEvidence.Observed, f.store.current()?.evidence)
        f.failWrite = false
        f.store.markLocalFollowupComplete(original.attemptId, 0)
        val restarted = OnchainSendAttemptStore(testDispatcher, f.keychain, f.service, kotlin.time.Clock.System)
        assertEquals(firstTxid, restarted.current()?.txid)
        assertTrue(restarted.current()?.localFollowupComplete == true)
    }

    @Test
    fun `post dispatch fallback never returns another attempt or wallet winner`() = test {
        for (failBroadcast in listOf(true, false)) {
            for (changeWallet in listOf(true, false)) {
                val f = Fixture()
                val original = f.unresolved()
                var current = original
                val other = original.copy(
                    attemptId = if (changeWallet) original.attemptId else "different-attempt",
                    walletId = if (changeWallet) "different-wallet" else original.walletId,
                    txid = "ef".repeat(32),
                    evidence = OnchainSendEvidence.Accepted,
                    localFollowupComplete = true,
                )
                val store = mock<OnchainSendAttemptStore>()
                whenever { store.current() }.doSuspendableAnswer { current }
                whenever { store.retainPreparedReceipt(any(), any(), any(), any()) }.thenReturn(original)
                whenever { store.broadcastPreparedCandidate(any(), any(), any(), any()) }.doSuspendableAnswer {
                    current = other
                    if (failBroadcast) error("native dispatch result unavailable")
                    OnchainSendOutcome.Unknown(nextTxid)
                }
                whenever { store.recordOutcome(any(), any(), any()) }.doSuspendableAnswer {
                    error("original operation is no longer current")
                }
                val sender = object : OnchainPreparedSender {
                    override suspend fun prepareInitial(attempt: OnchainSendAttempt): PreparedOnchainSend = error(
                        "unused"
                    )
                    override suspend fun prepareRecovery(
                        attempt: OnchainSendAttempt,
                        feeRateSatsPerVByte: ULong,
                        paymentDeadlineAt: Instant?
                    ) =
                        PreparedOnchainSend(f.receipt(nextTxid)) {
                            it()
                            OnchainSendOutcome.Unknown(nextTxid)
                        }
                }
                val result = OnchainSendCoordinator(store, sender, testDispatcher)
                    .retryOriginal(original.attemptId, original.walletId, 2uL) {}
                assertTrue(result.isFailure, "A later winner must not satisfy the original retry")
                assertEquals(other, current)
            }
        }
    }

    @Test
    fun `candidate fees retain authorized successor rate and select the actual older winner`() = test {
        val f = Fixture()
        val original = f.unresolved()
        val sender = object : OnchainPreparedSender {
            override suspend fun prepareInitial(attempt: OnchainSendAttempt): PreparedOnchainSend = error("unused")
            override suspend fun prepareRecovery(
                attempt: OnchainSendAttempt,
                feeRateSatsPerVByte: ULong,
                paymentDeadlineAt: Instant?
            ) =
                PreparedOnchainSend(f.receipt(nextTxid).copy(miningFeeSats = 150uL)) {
                    it()
                    val saved = kotlinx.serialization.json.Json.decodeFromString<OnchainSendAttempt>(
                        requireNotNull(f.saved)
                    )
                    assertEquals(3uL, saved.candidateFeeRates[nextTxid])
                    assertEquals(mapOf(firstTxid to 50uL, nextTxid to 150uL), saved.candidateMiningFees)
                    OnchainSendOutcome.Unknown(nextTxid)
                }
        }
        val coordinator = OnchainSendCoordinator(f.store, sender, testDispatcher)
        coordinator.retryOriginal(original.attemptId, original.walletId, 3uL) {}.getOrThrow()
        val reopened = OnchainSendAttemptStore(testDispatcher, f.keychain, f.service, kotlin.time.Clock.System)
        assertEquals(3uL, reopened.current()?.winningFeeRateSatsPerVByte)
        reopened.observeExactTransaction(firstTxid)
        assertEquals(1uL, reopened.current()?.winningFeeRateSatsPerVByte)
        assertEquals(mapOf(firstTxid to 1uL, nextTxid to 3uL), reopened.current()?.candidateFeeRates)
    }

    @Test
    fun `initial retention rejects zero and rates beyond the confirmed UInt32 range`() = test {
        val f = Fixture()
        val original = f.admit()
        for (feeRate in listOf(0uL, UInt.MAX_VALUE.toULong() + 1uL, ULong.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> {
                f.store.retainPreparedReceipt(
                    original.attemptId,
                    0,
                    f.receipt().copy(feeRateSatsPerVByte = feeRate),
                    false,
                )
            }
            assertTrue(f.store.current()?.candidateTxids?.isEmpty() == true)
        }
    }

    @Test
    fun `overflowing authorized recovery fee never reaches native preparation`() = test {
        assertEquals(999uL, OnchainRecoveryFeeRate.maximum)
        assertEquals(null, OnchainRecoveryFeeRate.parse("4294967296"))
        assertEquals(
            OnchainRecoveryFeeRate.maximum,
            OnchainRecoveryFeeRate.parse(OnchainRecoveryFeeRate.maximum.toString()),
        )
        assertEquals(null, OnchainRecoveryFeeRate.parse((OnchainRecoveryFeeRate.maximum + 1uL).toString()))
        assertEquals(null, OnchainRecoveryFeeRate.parse("0"))
        val f = Fixture()
        val original = f.unresolved()
        var preparations = 0
        val sender = object : OnchainPreparedSender {
            override suspend fun prepareInitial(attempt: OnchainSendAttempt): PreparedOnchainSend = error("unused")
            override suspend fun prepareRecovery(
                attempt: OnchainSendAttempt,
                feeRateSatsPerVByte: ULong,
                paymentDeadlineAt: Instant?,
            ): PreparedOnchainSend {
                preparations++
                return PreparedOnchainSend(f.receipt(nextTxid)) {
                    it()
                    OnchainSendOutcome.Unknown(nextTxid)
                }
            }
        }
        for (invalidRate in listOf(0uL, 1_000uL, 4_294_967_296uL, ULong.MAX_VALUE)) {
            val result = OnchainSendCoordinator(f.store, sender, testDispatcher)
                .retryOriginal(original.attemptId, original.walletId, invalidRate) {}
            assertTrue(result.isFailure)
        }
        assertEquals(0, preparations)
        assertFailsWith<IllegalArgumentException> {
            f.store.retainPreparedReceipt(
                original.attemptId,
                0,
                f.receipt(nextTxid).copy(feeRateSatsPerVByte = 1_000uL),
                true,
            )
        }
        assertEquals(listOf(firstTxid), f.store.current()?.candidateTxids)
    }

    @Test
    fun `cancellation approves exact original inputs to this wallet before dispatch and keeps the guard`() = test {
        val f = Fixture()
        val original = f.dispatchedUnknown()
        val cancellation = OnchainPreparedReceipt(cancelTxid, listOf(input), "bcrt1qown", 546uL, miningFeeSats = 300uL)
        var broadcasts = 0
        var approved = false
        val coordinator = OnchainSendCoordinator(f.store, mock(), testDispatcher)

        val outcome = coordinator.cancelOriginal(
            original.attemptId,
            original.walletId,
            5uL,
            prepareCancellation = { attempt, feeRate ->
                assertEquals(original, attempt)
                assertEquals(5uL, feeRate)
                PreparedOnchainSend(cancellation) {
                    it()
                    broadcasts++
                    OnchainSendOutcome.Accepted(cancelTxid)
                }
            },
        ) { preview ->
            assertEquals(cancellation.copy(feeRateSatsPerVByte = 5uL), preview)
            assertEquals(0, broadcasts)
            approved = true
        }.getOrThrow()

        assertEquals(OnchainSendOutcome.Accepted(cancelTxid), outcome)
        assertTrue(approved)
        assertEquals(1, broadcasts)
        // Saved before it went out, so a restart neither retries beside it nor underbids it
        val expected = original.copy(
            cancellationTxid = cancelTxid,
            cancellationFeeRateSatsPerVByte = 5uL,
            cancellationMiningFeeSats = 300uL,
        )
        assertEquals(expected, f.store.current())
        assertTrue(f.store.current()?.blocksNextSend == true)
    }

    @Test
    fun `replacement cancellation spends the same original inputs while the guard stays held`() = test {
        val f = Fixture()
        val original = f.dispatchedUnknown()
        val replacementTxid = "12".repeat(32)
        val dispatched = mutableListOf<OnchainPreparedReceipt>()
        val coordinator = OnchainSendCoordinator(f.store, mock(), testDispatcher)
        val prepareCancellation: suspend (OnchainSendAttempt, ULong) -> PreparedOnchainSend = { attempt, _ ->
            assertEquals(original.attemptId, attempt.attemptId)
            val txid = if (dispatched.isEmpty()) cancelTxid else replacementTxid
            val fee = if (dispatched.isEmpty()) 300uL else 400uL
            val inputs = attempt.originalInputs.orEmpty()
            val receipt = OnchainPreparedReceipt(txid, inputs, "bcrt1qown", 546uL, miningFeeSats = fee)
            PreparedOnchainSend(receipt) {
                it()
                dispatched += receipt
                OnchainSendOutcome.Accepted(txid)
            }
        }

        coordinator.cancelOriginal(original.attemptId, original.walletId, 5uL, prepareCancellation).getOrThrow()
        val replacement =
            coordinator.cancelOriginal(original.attemptId, original.walletId, 6uL, prepareCancellation).getOrThrow()

        assertEquals(OnchainSendOutcome.Accepted(replacementTxid), replacement)
        assertEquals(listOf(cancelTxid, replacementTxid), dispatched.map { it.txid })
        assertEquals(listOf(listOf(input), listOf(input)), dispatched.map { it.inputs })
        assertEquals(replacementTxid, f.store.current()?.cancellationTxid)
        assertTrue(f.store.current()?.blocksNextSend == true)
        // The earlier cancellation may still be in a mempool, so a replacement must pay a higher rate than it
        val underbid = coordinator.cancelOriginal(original.attemptId, original.walletId, 6uL, prepareCancellation)
        assertEquals(6uL, assertIs<OnchainSendCancelUnderbidsError>(underbid.exceptionOrNull()).feeRate)
        assertEquals(2, dispatched.size)
    }

    @Test
    fun `cancellation must pay more in total than a release that added other coins`() = test {
        val f = Fixture()
        val dispatched = f.dispatchedUnknown()
        // The release spent the original's coin with another, so at 5 sat/vB it paid 1,000 sats
        val released = dispatched.copy(
            releaseApproved = true,
            releaseTxid = "77".repeat(32),
            releaseFeeRateSatsPerVByte = 5uL,
            releaseMiningFeeSats = 1_000uL,
        )
        f.saved = Json.encodeToString(OnchainSendAttempt.serializer(), released)
        var approvals = 0
        var broadcasts = 0
        val coordinator = OnchainSendCoordinator(f.store, mock(), testDispatcher)
        suspend fun cancel(feeRate: ULong, fee: ULong) = coordinator.cancelOriginal(
            released.attemptId,
            released.walletId,
            feeRate,
            prepareCancellation = { _, _ ->
                val receipt = OnchainPreparedReceipt(cancelTxid, listOf(input), "bcrt1qown", 546uL, miningFeeSats = fee)
                PreparedOnchainSend(receipt) {
                    broadcasts++
                    OnchainSendOutcome.Accepted(cancelTxid)
                }
            },
        ) { approvals++ }

        // Over the original's coin alone, a Cancel payment at 6 sat/vB pays 700 sats
        val underbid = cancel(6uL, 700uL).exceptionOrNull()

        // 700 sats at 6 sat/vB is over 116.5 vB and at most 117 vB, so it must pay 1,117 sats: 10 sat/vB pays more
        // than 1,165 sats, and 9 sat/vB at most 1,050
        assertEquals(9uL, assertIs<OnchainSendCancelUnderbidsError>(underbid).feeRate)
        // At 9 sat/vB it pays more than the release, but not for its own size as well
        assertIs<OnchainSendCancelUnderbidsError>(cancel(9uL, 1_050uL).exceptionOrNull())
        // 1,001 sats at 6 sat/vB is up to 167 vB, so it must pay 1,167 sats
        val short = cancel(6uL, 1_001uL).exceptionOrNull()
        assertEquals(6uL, assertIs<OnchainSendCancelUnderbidsError>(short).feeRate)
        assertIs<OnchainSendCancelUnderbidsError>(cancel(6uL, 1_199uL).exceptionOrNull())
        assertEquals(0, approvals)
        assertEquals(0, broadcasts)
        // The suggested 10 sat/vB pays 1,166 sats, at least the release's 1,000 plus its 117 vB
        assertEquals(OnchainSendOutcome.Accepted(cancelTxid), cancel(10uL, 1_166uL).getOrThrow())
        assertEquals(1, approvals)
        assertEquals(1, broadcasts)
        // 1,200 sats at 6 sat/vB is up to 200 vB, so it pays exactly enough, had that Cancel payment not been sent
        f.saved = Json.encodeToString(OnchainSendAttempt.serializer(), released)
        assertTrue(cancel(6uL, 1_200uL).isSuccess)
        assertEquals(2, broadcasts)
        // A release saved before its fee was kept cannot be checked, so the network decides as before
        val unknownFee = released.copy(releaseMiningFeeSats = null)
        f.saved = Json.encodeToString(OnchainSendAttempt.serializer(), unknownFee)
        assertTrue(cancel(6uL, 700uL).isSuccess)
        assertEquals(3, broadcasts)
        assertEquals(cancelTxid, f.store.current()?.cancellationTxid)
    }

    @Test
    fun `cancellation must outbid a candidate's rate and fee`() = test {
        val f = Fixture()
        val dispatched = f.dispatchedUnknown()
        // A candidate to a P2TR recipient with change is larger than a Cancel payment's two P2WPKH outputs
        val bigCandidate = dispatched.copy(
            candidateFeeRates = mapOf(firstTxid to 4uL),
            candidateMiningFees = mapOf(firstTxid to 700uL),
        )
        f.saved = Json.encodeToString(OnchainSendAttempt.serializer(), bigCandidate)
        var broadcasts = 0
        val coordinator = OnchainSendCoordinator(f.store, mock(), testDispatcher)
        suspend fun cancel(feeRate: ULong, fee: ULong) = coordinator.cancelOriginal(
            bigCandidate.attemptId,
            bigCandidate.walletId,
            feeRate,
            prepareCancellation = { _, _ ->
                val receipt = OnchainPreparedReceipt(cancelTxid, listOf(input), "bcrt1qown", 546uL, miningFeeSats = fee)
                PreparedOnchainSend(receipt) {
                    broadcasts++
                    OnchainSendOutcome.Accepted(cancelTxid)
                }
            },
        )

        // A lower or equal rate never replaces the candidate
        assertEquals(4uL, assertIs<OnchainSendCancelUnderbidsError>(cancel(4uL, 2_000uL).exceptionOrNull()).feeRate)
        // 705 sats at 5 sat/vB is 141 vB: a higher rate, but less than the candidate's 700 sats plus its 141 vB
        val short = assertIs<OnchainSendCancelUnderbidsError>(cancel(5uL, 705uL).exceptionOrNull())
        assertEquals(5uL, short.feeRate)
        assertEquals(0, broadcasts)
        // 846 sats at 6 sat/vB is 141 vB and pays 700 plus 141 sats and more
        assertTrue(cancel(6uL, 846uL).isSuccess)
        assertEquals(1, broadcasts)
    }

    @Test
    fun `retry is refused beside a release or Cancel payment that may be in a mempool`() = test {
        val f = Fixture()
        val dispatched = f.dispatchedUnknown()
        val sender = object : OnchainPreparedSender {
            override suspend fun prepareInitial(attempt: OnchainSendAttempt): PreparedOnchainSend = error("unused")
            override suspend fun prepareRecovery(
                attempt: OnchainSendAttempt,
                feeRateSatsPerVByte: ULong,
                paymentDeadlineAt: Instant?,
            ): PreparedOnchainSend = error("a payment beside a cancellation must not be prepared")
        }
        val coordinator = OnchainSendCoordinator(f.store, sender, testDispatcher)
        for (sent in listOf(
            dispatched.copy(releaseApproved = true, releaseTxid = "77".repeat(32)),
            dispatched.copy(cancellationTxid = cancelTxid),
        )) {
            f.saved = Json.encodeToString(OnchainSendAttempt.serializer(), sent)

            val result = coordinator.retryOriginal(sent.attemptId, sent.walletId, 2uL) { error("not authorized") }

            assertIs<OnchainSendBlockedError>(result.exceptionOrNull())
            assertEquals(sent, f.store.current())
        }
    }

    @Test
    fun `speeding up replaces only a pending cancellation at a higher rate`() = test {
        val f = Fixture()
        val dispatched = f.dispatchedUnknown()
        val sent = dispatched.copy(cancellationTxid = cancelTxid, cancellationFeeRateSatsPerVByte = 5uL)
        f.saved = Json.encodeToString(OnchainSendAttempt.serializer(), sent)
        val pending = OnchainInputSpend(cancelTxid, listOf(input), listOf("bcrt1qown", "bcrt1qchange"), false)
        val bumpedTxid = "34".repeat(32)
        val bumps = mutableListOf<Pair<String, ULong>>()
        var approvals = 0
        val coordinator = OnchainSendCoordinator(f.store, mock(), testDispatcher)
        suspend fun speedUp(feeRate: ULong, spend: OnchainInputSpend?) = coordinator.speedUpCancellation(
            sent.attemptId,
            sent.walletId,
            feeRate,
            findPending = { spend },
            approve = { approvals++ },
        ) { txid, rate ->
            bumps += txid to rate
            bumpedTxid
        }

        assertIs<OnchainSendBlockedError>(speedUp(8uL, null).exceptionOrNull())
        assertIs<OnchainSendBlockedError>(speedUp(8uL, pending.copy(isFinal = true)).exceptionOrNull())
        val toRecipient = pending.copy(outputAddresses = listOf(sent.address))
        assertIs<OnchainSendBlockedError>(speedUp(8uL, toRecipient).exceptionOrNull())
        assertEquals(5uL, assertIs<OnchainSendCancelUnderbidsError>(speedUp(5uL, pending).exceptionOrNull()).feeRate)
        assertEquals(0, approvals)

        assertEquals(bumpedTxid, speedUp(8uL, pending).getOrThrow())

        assertEquals(1, approvals)
        assertEquals(listOf(cancelTxid to 8uL), bumps)
        assertEquals(bumpedTxid, f.store.current()?.cancellationTxid)
        assertEquals(8uL, f.store.current()?.cancellationFeeRateSatsPerVByte)
        assertTrue(f.store.current()?.blocksNextSend == true)
    }

    @Test
    fun `highest underbidding rate follows the prepared fee's rounding`() {
        // 141 vB pays 846 sats at 6 sat/vB; 8 sat/vB pays 1,128 and 7 sat/vB 987, below a 1,000-sat target
        assertEquals(7uL, highestFeeRateNotAbove(1_000uL, 846uL, 6uL))
        assertEquals(6uL, highestFeeRateNotAbove(846uL, 846uL, 6uL))
        assertEquals(1_000uL, highestFeeRateNotAbove(1_000uL, 1uL, 1uL))
        assertEquals(ULong.MAX_VALUE, highestFeeRateNotAbove(ULong.MAX_VALUE, 2uL, 2uL))
    }

    @Test
    fun `cancellation never dispatches changed inputs the recipient a candidate or an accepted payment`() = test {
        val f = Fixture()
        val original = f.dispatchedUnknown()
        val other = OnchainSendInput("22".repeat(32), 1u)
        val refused = listOf(
            OnchainPreparedReceipt(cancelTxid, listOf(other), "bcrt1qown", 546uL),
            OnchainPreparedReceipt(cancelTxid, listOf(input, other), "bcrt1qown", 546uL),
            OnchainPreparedReceipt(cancelTxid, listOf(input), original.address.uppercase(), 546uL),
            OnchainPreparedReceipt(firstTxid, listOf(input), "bcrt1qown", 546uL),
        )
        var broadcasts = 0
        val coordinator = OnchainSendCoordinator(f.store, mock(), testDispatcher)
        for (receipt in refused) {
            val prepared = PreparedOnchainSend(receipt) {
                broadcasts++
                error("dispatched")
            }
            val result = coordinator.cancelOriginal(
                original.attemptId,
                original.walletId,
                5uL,
                prepareCancellation = { _, _ -> prepared },
            ) { error("refused cancellation must not reach approval") }
            assertTrue(result.isFailure)
        }
        f.store.observeExactTransaction(firstTxid)
        val accepted = coordinator.cancelOriginal(
            original.attemptId,
            original.walletId,
            5uL,
            prepareCancellation = { _, _ -> error("an accepted payment must not be cancelled") },
        )
        assertIs<OnchainSendBlockedError>(accepted.exceptionOrNull())
        assertEquals(0, broadcasts)
        assertTrue(f.store.current()?.hasPositiveEvidence == true)
    }

    @Test
    fun `prepared broadcast success or error is cached once`() = test {
        for (fails in listOf(false, true)) {
            var sends = 0
            val prepared = PreparedOnchainSend(Fixture().receipt()) {
                it()
                sends++
                if (fails) error("native failed")
                OnchainSendOutcome.Unknown(firstTxid)
            }
            repeat(2) { runCatching { prepared.broadcast() } }
            assertEquals(1, sends)
        }
    }
}
