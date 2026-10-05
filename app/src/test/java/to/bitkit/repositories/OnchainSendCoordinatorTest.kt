package to.bitkit.repositories

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
import kotlin.test.assertTrue

class OnchainSendCoordinatorTest : BaseUnitTest() {
    private val input = OnchainSendInput("11".repeat(32), 0u)
    private val firstTxid = "ab".repeat(32)
    private val nextTxid = "cd".repeat(32)

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
            store = OnchainSendAttemptStore(testDispatcher, keychain, service)
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
            transferContext = OnchainTransferContext(1_100uL, 2_000uL), beforeSendAttempt = {},
            payerIdentity = "original-payer",
        )

        fun receipt(txid: String = firstTxid, amount: ULong = 1_000uL) =
            OnchainPreparedReceipt(txid, listOf(input), "bcrt1qrecipient", amount)

        suspend fun unresolved(): OnchainSendAttempt {
            val attempt = admit()
            store.retainPreparedReceipt(attempt.attemptId, 0, receipt(), false)
            return store.recordOutcome(attempt.attemptId, OnchainSendOutcome.Unknown(firstTxid), 0)
        }
    }

    @Test
    fun `receipt is durable before first broadcast and restart retains candidate`() = test {
        val f = Fixture()
        val attempt = f.admit()
        var sends = 0
        val sender = object : OnchainPreparedSender {
            override suspend fun prepareInitial(attempt: OnchainSendAttempt) = PreparedOnchainSend(f.receipt()) {
                assertTrue(requireNotNull(f.saved).contains(firstTxid))
                sends++
                OnchainSendOutcome.Unknown(firstTxid)
            }
            override suspend fun prepareRecovery(
                attempt: OnchainSendAttempt,
                feeRateSatsPerVByte: ULong,
            ): PreparedOnchainSend =
                error("not requested")
        }
        assertTrue(OnchainSendCoordinator(f.store, sender, testDispatcher).sendInitial(attempt).isSuccess)
        val reopened = OnchainSendAttemptStore(testDispatcher, f.keychain, f.service)
        assertEquals(listOf(firstTxid), reopened.current()?.candidateTxids)
        assertEquals(listOf(input), reopened.current()?.originalInputs)
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
                sends++
                OnchainSendOutcome.Accepted(firstTxid)
            }
            override suspend fun prepareRecovery(
                attempt: OnchainSendAttempt,
                feeRateSatsPerVByte: ULong,
            ): PreparedOnchainSend =
                error("not requested")
        }
        assertTrue(OnchainSendCoordinator(f.store, sender, testDispatcher).sendInitial(attempt).isFailure)
        assertEquals(0, sends)
        assertTrue(f.store.current()?.blocksNextSend == true)
    }

    @Test
    fun `initial Max actual amount becomes fixed recovery amount with original context`() = test {
        val f = Fixture()
        val attempt = f.admit(isMax = true)
        var authorizations = 0
        val sender = object : OnchainPreparedSender {
            override suspend fun prepareInitial(attempt: OnchainSendAttempt) = PreparedOnchainSend(
                f.receipt(amount = 900uL)
            ) {
                OnchainSendOutcome.Unknown(firstTxid)
            }
            override suspend fun prepareRecovery(
                attempt: OnchainSendAttempt,
                feeRateSatsPerVByte: ULong,
            ): PreparedOnchainSend {
                assertEquals(900uL, attempt.amountSats)
                assertEquals(listOf(input), attempt.originalInputs)
                assertEquals("order-1", attempt.orderId)
                assertEquals("wallet-1", attempt.walletId)
                assertEquals(null, attempt.requestId)
                assertEquals("channel-1", attempt.channelId)
                assertEquals(OnchainTransferContext(1_100uL, 2_000uL), attempt.transferContext)
                return PreparedOnchainSend(f.receipt(nextTxid, 900uL)) { OnchainSendOutcome.Unknown(nextTxid) }
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
                ): PreparedOnchainSend {
                    if (mode == "wallet") f.walletIndex = 1
                    val receipt = when (mode) {
                        "input" -> f.receipt(nextTxid).copy(inputs = listOf(OnchainSendInput("22".repeat(32), 1u)))
                        "amount" -> f.receipt(nextTxid, 999uL)
                        else -> f.receipt(nextTxid)
                    }
                    return PreparedOnchainSend(receipt) {
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
            override suspend fun prepareRecovery(attempt: OnchainSendAttempt, feeRateSatsPerVByte: ULong) =
                PreparedOnchainSend(f.receipt(nextTxid)) {
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
            ): PreparedOnchainSend {
                f.store.observeExactTransaction(firstTxid)
                return PreparedOnchainSend(f.receipt(nextTxid)) {
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
            PreparedOnchainSend(f.receipt(amount = 900uL)) { OnchainSendOutcome.Unknown(firstTxid) },
        )
        whenever(native.prepareFixed(initial.address, 900uL, 2uL, listOf(input), 0)).thenReturn(
            PreparedOnchainSend(f.receipt(nextTxid, 900uL)) { OnchainSendOutcome.Unknown(nextTxid) },
        )
        val coordinator = OnchainSendCoordinator(f.store, OnchainPreparedSenderAdapter(native), testDispatcher)
        coordinator.sendInitial(initial).getOrThrow()
        coordinator.retryOriginal(initial.attemptId, initial.walletId, 2uL) {}.getOrThrow()
        verify(native).prepareMax(initial.address, true, 1uL, 0)
        verify(native).prepareFixed(initial.address, 900uL, 2uL, listOf(input), 0)
    }

    @Test
    fun `original authorization failure retains prepared candidate without broadcasting`() = test {
        val f = Fixture()
        val attempt = f.unresolved()
        var prepares = 0
        var broadcasts = 0
        val sender = object : OnchainPreparedSender {
            override suspend fun prepareInitial(attempt: OnchainSendAttempt): PreparedOnchainSend = error(
                "not requested",
            )
            override suspend fun prepareRecovery(
                attempt: OnchainSendAttempt,
                feeRateSatsPerVByte: ULong,
            ): PreparedOnchainSend {
                prepares++
                return PreparedOnchainSend(f.receipt(nextTxid)) {
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
        assertEquals(listOf(firstTxid, nextTxid), f.store.current()?.candidateTxids)
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
            ): PreparedOnchainSend {
                entered.complete(Unit)
                finish.await()
                return PreparedOnchainSend(f.receipt(nextTxid)) {
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
        assertEquals(retained, authorizedRecord)
        assertEquals(listOf(firstTxid, nextTxid), retained?.candidateTxids)
        assertEquals(original.payerIdentity, retained?.payerIdentity)
        assertEquals(original.requestId, retained?.requestId)
        assertEquals(original.orderId, retained?.orderId)
        assertEquals(original.originalInputs, retained?.originalInputs)
        assertEquals(OnchainSendEvidence.Pending, retained?.evidence)
        val restarted = OnchainSendAttemptStore(testDispatcher, f.keychain, f.service)
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
            ): PreparedOnchainSend {
                assertEquals(0, active)
                prepares++
                val txid = if (prepares == 1) nextTxid else "ef".repeat(32)
                return PreparedOnchainSend(f.receipt(txid)) {
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
        val reopened = OnchainSendAttemptStore(testDispatcher, f.keychain, f.service)
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
                feeRateSatsPerVByte: ULong
            ): PreparedOnchainSend {
                f.failWrite = true
                runCatching { f.store.observeExactTransaction(firstTxid) }
                return PreparedOnchainSend(f.receipt(nextTxid)) {
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
        val restarted = OnchainSendAttemptStore(testDispatcher, f.keychain, f.service)
        assertEquals(firstTxid, restarted.current()?.txid)
        assertTrue(restarted.current()?.localFollowupComplete == true)
    }

    @Test
    fun `overflowing authorized recovery fee never reaches native preparation`() = test {
        assertEquals(UInt.MAX_VALUE.toULong(), OnchainRecoveryFeeRate.maximum)
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
            ): PreparedOnchainSend {
                preparations++
                return PreparedOnchainSend(f.receipt(nextTxid)) { OnchainSendOutcome.Unknown(nextTxid) }
            }
        }
        for (invalidRate in listOf(4_294_967_296uL, ULong.MAX_VALUE)) {
            val result = OnchainSendCoordinator(f.store, sender, testDispatcher)
                .retryOriginal(original.attemptId, original.walletId, invalidRate) {}
            assertTrue(result.isFailure)
        }
        assertEquals(0, preparations)
        assertEquals(listOf(firstTxid), f.store.current()?.candidateTxids)
    }

    @Test
    fun `prepared broadcast success or error is cached once`() = test {
        for (fails in listOf(false, true)) {
            var sends = 0
            val prepared = PreparedOnchainSend(Fixture().receipt()) {
                sends++
                if (fails) error("native failed")
                OnchainSendOutcome.Unknown(firstTxid)
            }
            repeat(2) { runCatching { prepared.broadcast() } }
            assertEquals(1, sends)
        }
    }
}
