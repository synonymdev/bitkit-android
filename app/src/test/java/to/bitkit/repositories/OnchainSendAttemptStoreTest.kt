package to.bitkit.repositories

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlin.time.ExperimentalTime::class)
class OnchainSendAttemptStoreTest : BaseUnitTest() {
    private val key = Keychain.Key.ONCHAIN_SEND_ATTEMPT.name

    @Test
    fun `backup proof capture blocks receipt retention until the snapshot is complete`() = test {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        val attempt = store.admitForTest()
        val captureEntered = CompletableDeferred<Unit>()
        val finishCapture = CompletableDeferred<Unit>()
        val snapshot = async {
            store.backupSnapshot(0) {
                captureEntered.complete(Unit)
                finishCapture.await()
                emptyList()
            }
        }
        captureEntered.await()
        val receipt = OnchainPreparedReceipt(
            "ab".repeat(32), listOf(OnchainSendInput("11".repeat(32), 0u)), attempt.address, attempt.amountSats,
        )
        val retain = async { store.retainPreparedReceipt(attempt.attemptId, 0, receipt, false) }
        runCurrent()
        assertEquals(false, retain.isCompleted)
        finishCapture.complete(Unit)
        assertNull(snapshot.await().first?.txid)
        assertEquals(receipt.txid, retain.await().txid)
    }

    @Test
    fun `expired first Shop submission retains empty guard until original proof cleanup succeeds`() = test {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        whenever(keychain.delete(key, 0)).doSuspendableAnswer { saved = null }
        val service = mock<LightningService>()
        val store = OnchainSendAttemptStore(testDispatcher, keychain, service, kotlin.time.Clock.System)
        val attempt = store.admit(
            walletId = "wallet0", requestId = PaykitPaymentRequestId("request", "payer"),
            orderId = "order", address = "bcrt1qrecipient", amountSats = 1000uL,
            isMaxAmount = false, feeRateSatsPerVByte = 1uL, isTransfer = false,
            channelId = null, tags = emptyList(), beforeSendAttempt = {}, payerIdentity = "payer",
        )
        val receipt = OnchainPreparedReceipt(
            "ab".repeat(32), listOf(OnchainSendInput("11".repeat(32), 0u)), attempt.address, attempt.amountSats,
        )
        store.retainPreparedReceipt(attempt.attemptId, 0, receipt, false)
        assertFailsWith<OnchainSendNotDispatchedError> {
            store.broadcastPreparedCandidate(attempt.attemptId, 0, receipt.txid) {
                throw to.bitkit.utils.ServiceError.PaymentDeadlineExpired()
            }
        }
        store.releaseBeforeDispatch(attempt.attemptId, 0)
        val pending = requireNotNull(store.current())
        assertTrue(pending.preparationPending)
        assertEquals(attempt.requestId, pending.requestId)
        assertEquals(attempt.payerIdentity, pending.payerIdentity)
        assertTrue(pending.candidateTxids.isEmpty())
        val reopened = OnchainSendAttemptStore(testDispatcher, keychain, service, kotlin.time.Clock.System)
        assertEquals(false, reopened.releaseInterruptedShopPreparation { false })
        assertEquals(pending, reopened.current())
        assertEquals(true, reopened.releaseInterruptedShopPreparation {
            assertEquals(pending, it)
            true
        })
        assertNull(reopened.current())
    }

    @Test
    fun `first submission deadline failure clears only never dispatched receipt`() = test {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        whenever(keychain.delete(key, 0)).doSuspendableAnswer { saved = null }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        val attempt = store.admitForTest()
        val receipt = OnchainPreparedReceipt(
            "ab".repeat(32), listOf(OnchainSendInput("11".repeat(32), 0u)), attempt.address, attempt.amountSats,
        )
        store.retainPreparedReceipt(attempt.attemptId, 0, receipt, false)
        assertFailsWith<OnchainSendNotDispatchedError> {
            store.broadcastPreparedCandidate(attempt.attemptId, 0, receipt.txid) {
                throw to.bitkit.utils.ServiceError.PaymentDeadlineExpired()
            }
        }
        store.releaseBeforeDispatch(attempt.attemptId, 0)
        assertNull(store.current())
        store.admitForTest()
    }

    @Test
    fun `deadline on later submission or reopened guard never releases original candidate`() = test {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        val service = mock<LightningService>()
        val store = OnchainSendAttemptStore(testDispatcher, keychain, service, kotlin.time.Clock.System)
        val attempt = store.admitForTest()
        val receipt = OnchainPreparedReceipt(
            "ab".repeat(32), listOf(OnchainSendInput("11".repeat(32), 0u)), attempt.address, attempt.amountSats,
        )
        val retained = store.retainPreparedReceipt(attempt.attemptId, 0, receipt, false)
        store.broadcastPreparedCandidate(attempt.attemptId, 0, receipt.txid) {
            OnchainSendOutcome.Unknown(receipt.txid)
        }
        // Even before outcome persistence, this process knows dispatch was attempted.
        assertFailsWith<to.bitkit.utils.ServiceError.PaymentDeadlineExpired> {
            store.broadcastPreparedCandidate(attempt.attemptId, 0, receipt.txid) {
                throw to.bitkit.utils.ServiceError.PaymentDeadlineExpired()
            }
        }
        assertEquals(retained, store.current())
        val reopened = OnchainSendAttemptStore(testDispatcher, keychain, service, kotlin.time.Clock.System)
        assertFailsWith<to.bitkit.utils.ServiceError.PaymentDeadlineExpired> {
            reopened.broadcastPreparedCandidate(attempt.attemptId, 0, receipt.txid) {
                throw to.bitkit.utils.ServiceError.PaymentDeadlineExpired()
            }
        }
        assertEquals(retained, reopened.current())
    }

    @Test
    fun `preparation cancellation cleanup releases only exact empty guard and never a receipt`() = test {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        whenever(keychain.delete(key, 0)).doSuspendableAnswer { saved = null }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        val empty = store.admitForTest()
        store.releaseBeforeDispatch("foreign-attempt", 0)
        assertEquals(empty, store.current())
        store.releaseBeforeDispatch(empty.attemptId, 0)
        assertNull(store.current())
        val original = store.admitForTest()
        val receipt = OnchainPreparedReceipt(
            "ab".repeat(32),
            listOf(OnchainSendInput("11".repeat(32), 0u)),
            original.address,
            original.amountSats,
        )
        val retained = store.retainPreparedReceipt(original.attemptId, 0, receipt, false)
        store.releaseBeforeDispatch(original.attemptId, 0)
        assertEquals(retained, store.current())
        assertFailsWith<OnchainSendBlockedError> { store.admitForTest() }
    }

    @Test
    fun `restart never releases legacy empty guards or recorded candidates`() = test {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        val original = store.admitForTest()
        val reopened = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        assertEquals(false, reopened.releaseInterruptedShopPreparation { error("legacy guard must remain") })
        val receipt = OnchainPreparedReceipt(
            "ab".repeat(32),
            listOf(OnchainSendInput("11".repeat(32), 0u)),
            original.address,
            original.amountSats,
        )
        val retained = store.retainPreparedReceipt(original.attemptId, 0, receipt, false)
        assertEquals(false, reopened.releaseInterruptedShopPreparation { error("signed candidate must remain") })
        assertEquals(retained, reopened.current())
    }

    @Test
    fun `admission is durable before a callback consumes request details`() = test {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        store.admitForTest {
            assertTrue(saved != null, "guard must exist before proof mutation")
        }
    }

    @Test
    fun `failed admission save does not consume request details`() = test {
        val keychain = mock<Keychain>()
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { error("storage failure") }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        var callbacks = 0
        assertFailsWith<IllegalStateException> { store.admitForTest { callbacks++ } }
        assertEquals(0, callbacks)
    }

    @Test
    fun `original contact is durable before dispatch and survives reopening`() = test {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        val contact = "original-contact"
        val attempt = store.admitForTest(contactPublicKey = contact) {
            assertTrue(requireNotNull(saved).contains(contact), "original contact must be saved before dispatch")
        }
        assertEquals(contact, (attempt.backupFollowup?.contact as? kotlinx.serialization.json.JsonPrimitive)?.content)
        val reopened = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        assertEquals(attempt.backupFollowup?.contact, reopened.current()?.backupFollowup?.contact)
    }

    @Test
    fun `admission followup timestamp uses the injected clock`() = test {
        val keychain = mock<Keychain>()
        val clock = mock<kotlin.time.Clock>()
        whenever(clock.now()).thenReturn(kotlin.time.Instant.fromEpochMilliseconds(123_456L))
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), clock)
        assertEquals("123456", store.admitForTest().backupFollowup?.createdAtMillis)
    }

    @Test
    fun `restored exact candidate with contact observes without resend and acknowledges`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/active-onchain-attempt-golden.json")).readBytes()
        val backup = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<to.bitkit.models.WalletBackupV1>(bytes.decodeToString())
        val state = requireNotNull(backup.paykitPaymentState)
        val wire = requireNotNull(state.activeOnchainAttempt)
        wire.validateProofs(state.pendingProofs, "wallet0")
        val original = wire.restored("regtest", wire.wallet.binding, "wallet0", 2)
        val values = mutableMapOf<Int, String>()
        val keychain = mock<Keychain>()
        val service = mock<to.bitkit.services.LightningService>()
        whenever(service.currentWalletIndex).thenReturn(2)
        whenever(keychain.loadString(eq(key), any())).thenAnswer { values[it.getArgument(1)] }
        whenever(keychain.upsertString(eq(key), any(), any())).doSuspendableAnswer {
            values[it.getArgument(2)] = it.getArgument(1)
        }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, service, kotlin.time.Clock.System)
        store.restoreActive(original)
        assertFailsWith<OnchainSendBlockedError> { store.admitForTest() }
        assertNull(store.observeExactTransaction("00".repeat(32)))
        val observed = store.observeExactTransaction(requireNotNull(wire.txid))
        assertEquals(OnchainSendEvidence.Observed, observed?.evidence)
        assertEquals(wire.candidateTxids, observed?.candidateTxids)
        val reopened = OnchainSendAttemptStore(testDispatcher, keychain, service, kotlin.time.Clock.System)
        assertEquals(observed, reopened.current())
        assertFailsWith<OnchainSendBlockedError> { reopened.admitForTest() }
        val other = original.copy(attemptId = "00000000-0000-4000-8000-000000000009")
        assertFailsWith<IllegalStateException> { reopened.restoreActive(other) }
        store.markLocalFollowupComplete(original.attemptId, 2)
        assertEquals(observed?.copy(localFollowupComplete = true), reopened.current())
    }

    @Test
    fun `restoring same accepted operation preserves progressed fee and completion`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/active-onchain-attempt-golden.json")).readBytes()
        val wire = requireNotNull(
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<to.bitkit.models.WalletBackupV1>(bytes.decodeToString())
                .paykitPaymentState?.activeOnchainAttempt,
        )
        val accepted = wire.copy(status = "accepted", candidateFeeRates = mapOf(requireNotNull(wire.txid) to "4"),
            followup = wire.followup?.copy(contact = null))
            .restored("regtest", wire.wallet.binding, "wallet0", 0)
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        store.restoreActive(accepted)
        store.retainWinningFee(accepted.attemptId, 0, requireNotNull(accepted.txid), 777u)
        val progressed = store.current()
        store.restoreActive(accepted)
        assertEquals(progressed, store.current())
        assertFailsWith<IllegalStateException> { store.restoreActive(accepted.copy(address = "different-recipient")) }
        assertFailsWith<IllegalStateException> { store.restoreActive(accepted.copy(payerIdentity = "different-payer")) }
        store.markLocalFollowupComplete(accepted.attemptId, 0)
        val completed = store.current()
        store.restoreActive(accepted)
        assertEquals(completed, store.current())
    }

    @Test
    fun `restored supported accepted context resets acknowledgement and finishes idempotently`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/active-onchain-attempt-golden.json")).readBytes()
        val backup = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<to.bitkit.models.WalletBackupV1>(bytes.decodeToString())
        val wire = requireNotNull(backup.paykitPaymentState?.activeOnchainAttempt)
        val accepted = wire.copy(status = "accepted", candidateFeeRates = mapOf(requireNotNull(wire.txid) to "4"),
            followup = wire.followup?.copy(contact = null))
            .restored("regtest", wire.wallet.binding, "wallet0", 0)
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        store.restoreActive(accepted)
        assertTrue(store.current()?.localFollowupComplete == false)
        assertFailsWith<OnchainSendBlockedError> { store.admitForTest() }
        store.markLocalFollowupComplete(accepted.attemptId, 0)
        store.markLocalFollowupComplete(accepted.attemptId, 0)
        assertTrue(store.current()?.localFollowupComplete == true)
    }

    @Test
    fun `missing imported followup stays blocked while existing local positive context can acknowledge`() = test {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        val admitted = store.admitForTest()
        val local = admitted.copy(backupFollowup = null)
        saved = kotlinx.serialization.json.Json.encodeToString(OnchainSendAttempt.serializer(), local)
        store.recordOutcome(local.attemptId, OnchainSendOutcome.Accepted("ab".repeat(32)), 0)
        store.markLocalFollowupComplete(local.attemptId, 0)
        assertTrue(store.current()?.localFollowupComplete == true)
        val imported = requireNotNull(store.current()).copy(restoredFromBackup = true, localFollowupComplete = false)
        store.restoreActive(imported)
        assertFailsWith<IllegalStateException> { store.markLocalFollowupComplete(imported.attemptId, 0) }
        assertFailsWith<OnchainSendBlockedError> { store.admitForTest() }
    }

    @Test
    fun `reopen blocks unresolved send until exact transaction is observed and followup is complete`() = test {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer {
            saved = it.getArgument(1)
        }
        val firstStore = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        val attempt = firstStore.admitForTest()
        firstStore.recordOutcome(attempt.attemptId, OnchainSendOutcome.Unknown("ab".repeat(32)), attempt.walletIndex)

        val reopened = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        assertFailsWith<OnchainSendBlockedError> { reopened.admitForTest() }
        assertNull(reopened.observeExactTransaction("cd".repeat(32)))
        assertFailsWith<OnchainSendBlockedError> { reopened.admitForTest() }

        val observed = reopened.observeExactTransaction("ab".repeat(32))
        assertEquals(OnchainSendEvidence.Observed, observed?.evidence)
        assertFailsWith<OnchainSendBlockedError> { reopened.admitForTest() }
        reopened.markLocalFollowupComplete(attempt.attemptId, attempt.walletIndex)
        assertTrue(reopened.admitForTest().attemptId != attempt.attemptId)
    }

    @Test
    fun `transfer balance context is durable before dispatch and survives reopen`() = test {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        val context = OnchainTransferContext(txTotalSats = 99_000uL, preTransferOnchainSats = 125_000uL)
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        val attempt = store.admit(
            walletId = "wallet-1", requestId = null, orderId = "order-1", address = "bcrt1qfunding",
            amountSats = 98_000uL, isMaxAmount = false, feeRateSatsPerVByte = 1uL, isTransfer = true,
            channelId = null, tags = emptyList(), transferContext = context, beforeSendAttempt = {},
        )

        val reopened = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        assertEquals(context, reopened.current()?.transferContext)
        assertEquals(OnchainSendEvidence.Pending, reopened.current()?.evidence)
        assertFailsWith<OnchainSendBlockedError> { reopened.admitForTest() }
        store.recordOutcome(attempt.attemptId, OnchainSendOutcome.Accepted("ab".repeat(32)), attempt.walletIndex)
        assertEquals(context, reopened.current()?.transferContext)
    }

    @Test
    fun `corrupt attempt refuses a new send without replacing evidence`() = test {
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenReturn("not-json")
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)

        assertFailsWith<OnchainSendAttemptUnreadableError> { store.admitForTest() }
        verify(keychain).loadString(key, 0)
    }

    @Test
    fun `outcome remains in the admitted node wallet after config context changes`() = test {
        val values = mutableMapOf<Int, String>()
        val keychain = mock<Keychain>()
        val service = mock<LightningService>()
        var currentIndex = 7
        whenever(service.currentWalletIndex).thenAnswer { currentIndex }
        whenever(keychain.loadString(eq(key), any())).thenAnswer { values[it.getArgument(1)] }
        whenever(keychain.upsertString(eq(key), any(), any())).doSuspendableAnswer {
            values[it.getArgument(2)] = it.getArgument(1)
        }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, service, kotlin.time.Clock.System)
        val attempt = store.admitForTest()
        assertEquals(7, attempt.walletIndex)
        currentIndex = 8
        store.recordOutcome(attempt.attemptId, OnchainSendOutcome.Accepted("ab".repeat(32)), attempt.walletIndex)
        assertNull(store.current())
        currentIndex = 7
        assertEquals(OnchainSendEvidence.Accepted, store.current()?.evidence)
        assertFailsWith<OnchainSendBlockedError> { store.admitForTest() }
    }

    @Test
    fun `known accepted survives write failures in memory while reopened pending stays blocked`() = test {
        var saved: String? = null
        var failWrite = false
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer {
            if (failWrite) error("storage unavailable")
            saved = it.getArgument(1)
        }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        val attempt = store.admitForTest()
        val txid = "ab".repeat(32)
        failWrite = true
        assertFailsWith<IllegalStateException> {
            store.recordOutcome(attempt.attemptId, OnchainSendOutcome.Accepted(txid), attempt.walletIndex)
        }
        assertEquals(txid, store.current()?.txid)
        assertEquals(OnchainSendEvidence.Accepted, store.current()?.evidence)
        assertFailsWith<IllegalStateException> { store.markLocalFollowupComplete(attempt.attemptId, attempt.walletIndex) }
        assertFailsWith<OnchainSendBlockedError> { store.admitForTest() }
        val reopened = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        assertEquals(OnchainSendEvidence.Pending, reopened.current()?.evidence)
        assertNull(reopened.current()?.txid)
        assertFailsWith<OnchainSendBlockedError> { reopened.admitForTest() }
    }

    @Test
    fun `parallel admissions dispatch only one before-send callback`() = test {
        var saved: String? = null
        var callbacks = 0
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val first = async { store.admitForTest { callbacks++; entered.complete(Unit); finish.await() } }
        entered.await()
        val second = async { runCatching { store.admitForTest { callbacks++ } } }
        finish.complete(Unit)
        first.await()
        assertTrue(second.await().exceptionOrNull() is OnchainSendBlockedError)
        assertEquals(1, callbacks)
    }

    @Test
    fun `cancel after admission and failed result save preserve durable pending guard`() = test {
        var saved: String? = null
        var failWrite = false
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer {
            if (failWrite) error("storage unavailable")
            saved = it.getArgument(1)
        }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        val admitted = CompletableDeferred<OnchainSendAttempt>()
        val send = launch { admitted.complete(store.admitForTest()); kotlinx.coroutines.awaitCancellation() }
        val attempt = admitted.await()
        send.cancelAndJoin()
        failWrite = true
        assertFailsWith<IllegalStateException> {
            store.recordOutcome(attempt.attemptId, OnchainSendOutcome.Accepted("ab".repeat(32)), attempt.walletIndex)
        }
        val reopened = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        assertEquals(OnchainSendEvidence.Pending, reopened.current()?.evidence)
        assertFailsWith<OnchainSendBlockedError> { reopened.admitForTest() }
    }

    @Test
    fun `late unknown outcome cannot overwrite exact positive observation`() = test {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        val attempt = store.admitForTest()
        val txid = "ab".repeat(32)
        store.recordOutcome(attempt.attemptId, OnchainSendOutcome.Unknown(txid), attempt.walletIndex)
        store.observeExactTransaction(txid)

        val late = store.recordOutcome(attempt.attemptId, OnchainSendOutcome.Unknown(txid), attempt.walletIndex)

        assertEquals(OnchainSendEvidence.Observed, late.evidence)
        assertEquals(txid, store.current()?.txid)
        assertEquals(OnchainSendEvidence.Observed, store.current()?.evidence)
        assertFailsWith<OnchainSendBlockedError> { store.admitForTest() }
    }

    private suspend fun OnchainSendAttemptStore.admitForTest(
        contactPublicKey: String? = null,
        beforeSendAttempt: suspend () -> Unit = {
        }
    ): OnchainSendAttempt = admit(
        walletId = "wallet-1",
        requestId = null,
        orderId = null,
        address = "bcrt1qrecipient",
        amountSats = 1_000uL,
        isMaxAmount = false,
        feeRateSatsPerVByte = 1uL,
        isTransfer = false,
        channelId = null,
        tags = emptyList(),
        beforeSendAttempt = beforeSendAttempt,
        contactPublicKey = contactPublicKey,
    )
}
