package to.bitkit.repositories

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.lightningdevkit.ldknode.OutPoint
import org.lightningdevkit.ldknode.SpendableUtxo
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.ActiveOnchainAttemptBackup
import to.bitkit.models.WalletScope
import to.bitkit.services.LightningService
import to.bitkit.test.BaseUnitTest
import java.io.FileOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

private val lenientJson = Json { ignoreUnknownKeys = true }

@OptIn(kotlin.time.ExperimentalTime::class, ExperimentalCoroutinesApi::class)
class OnchainSendAttemptStoreTest : BaseUnitTest() {
    private val key = Keychain.Key.ONCHAIN_SEND_ATTEMPT.name

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `release adds wallet coins when the original coins fall short`() {
        fun utxo(byte: String, value: ULong) = SpendableUtxo(OutPoint(byte.repeat(32), 0u), value)
        val original = utxo("11", 1_200uL)
        val small = utxo("22", 5_000uL)
        val large = utxo("33", 90_000uL)
        val medium = utxo("44", 20_000uL)
        val originalInput = setOf(OnchainSendInput("11".repeat(32), 0u))

        val sets = releaseInputSets(listOf(small, original, large, medium), originalInput)

        assertEquals(
            listOf(
                listOf(1_200uL),
                listOf(1_200uL, 90_000uL),
                listOf(1_200uL, 90_000uL, 20_000uL),
                listOf(1_200uL, 90_000uL, 20_000uL, 5_000uL),
            ),
            sets.map { set -> set.map { it.valueSats } },
        )
        assertTrue(releaseInputSets(listOf(small, large), originalInput).isEmpty(), "A release needs an original coin")
    }

    @Test
    fun `prepared candidate fees survive disk reopen without promoting unknown outcomes`() = test {
        val file = temporaryFolder.newFile()
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { file.readText().takeIf(String::isNotEmpty) }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer {
            FileOutputStream(file).use { output ->
                output.write(it.getArgument<String>(1).toByteArray())
                output.fd.sync()
            }
        }
        val service = mock<LightningService>()
        val store = OnchainSendAttemptStore(testDispatcher, keychain, service, Clock.System)
        val attempt = store.admitForTest(walletId = WalletScope.default)
        val receipt = OnchainPreparedReceipt(
            "ab".repeat(32),
            listOf(OnchainSendInput("11".repeat(32), 0u)),
            attempt.address,
            attempt.amountSats,
            miningFeeSats = 141uL,
        )
        store.retainPreparedReceipt(attempt.attemptId, 0, receipt, false)
        store.recordOutcome(attempt.attemptId, OnchainSendOutcome.Unknown(receipt.txid), 0)
        val replacement = receipt.copy(txid = "cd".repeat(32), miningFeeSats = 281uL)
        store.retainPreparedReceipt(attempt.attemptId, 0, replacement, true)
        val reopened = OnchainSendAttemptStore(testDispatcher, keychain, service, Clock.System)
        val pending = requireNotNull(reopened.current())
        assertEquals(mapOf(receipt.txid to 141uL, replacement.txid to 281uL), pending.candidateMiningFees)
        assertFalse(pending.hasPositiveEvidence)
        assertTrue(pending.blocksNextSend)
        assertNull(pending.verifiedWinningFeeSats)
        assertFailsWith<IllegalArgumentException> {
            reopened.retainPreparedReceipt(attempt.attemptId, 0, replacement.copy(miningFeeSats = 282uL), true)
        }
        assertEquals(pending, reopened.current())
        val accepted = reopened.recordOutcome(attempt.attemptId, OnchainSendOutcome.Accepted(replacement.txid), 0)
        val backup = ActiveOnchainAttemptBackup.from(accepted, "regtest", "11".repeat(32))
        val restored = backup.restored("regtest", backup.wallet.binding, attempt.walletId, 0)
        // The backup carries each candidate's mining fee, so a release after a restore still outbids it.
        assertEquals(pending.candidateMiningFees, restored.candidateMiningFees)
        reopened.restoreActive(restored)
        assertEquals(accepted.candidateMiningFees, reopened.current()?.candidateMiningFees)
    }

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
            "ab".repeat(32),
            listOf(OnchainSendInput("11".repeat(32), 0u)),
            attempt.address,
            attempt.amountSats,
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
            "ab".repeat(32),
            listOf(OnchainSendInput("11".repeat(32), 0u)),
            attempt.address,
            attempt.amountSats,
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
        assertEquals(
            true,
            reopened.releaseInterruptedShopPreparation {
                assertEquals(pending, it)
                true
            }
        )
        assertNull(reopened.current())
    }

    @Test
    fun `restart releases prepared candidate only when native dispatch was never entered`() = test {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        whenever(keychain.delete(key, 0)).doSuspendableAnswer { saved = null }
        val service = mock<LightningService>()
        val store = OnchainSendAttemptStore(testDispatcher, keychain, service, kotlin.time.Clock.System)
        val original = store.admitForTest(walletId = WalletScope.default)
        val receipt = OnchainPreparedReceipt(
            "ab".repeat(32),
            listOf(OnchainSendInput("11".repeat(32), 0u)),
            original.address,
            original.amountSats
        )
        store.retainPreparedReceipt(original.attemptId, 0, receipt, false)
        assertEquals(receipt.txid, store.current()?.txid)
        val reopened = OnchainSendAttemptStore(testDispatcher, keychain, service, kotlin.time.Clock.System)
        assertNull(reopened.current())
        reopened.admitForTest(walletId = WalletScope.default)
    }

    @Test
    fun `failed durable native dispatch marker stops broadcast`() = test {
        var saved: String? = null
        var failMarker = false
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer {
            if (failMarker) error("dispatch marker unavailable")
            saved = it.getArgument(1)
        }
        whenever(keychain.delete(key, 0)).doSuspendableAnswer { saved = null }
        val service = mock<LightningService>()
        val store = OnchainSendAttemptStore(testDispatcher, keychain, service, kotlin.time.Clock.System)
        val original = store.admitForTest(walletId = WalletScope.default)
        val receipt = OnchainPreparedReceipt(
            "ab".repeat(32),
            listOf(OnchainSendInput("11".repeat(32), 0u)),
            original.address,
            original.amountSats
        )
        store.retainPreparedReceipt(original.attemptId, 0, receipt, false)
        failMarker = true
        var broadcasts = 0
        assertFailsWith<IllegalStateException> {
            store.broadcastPreparedCandidate(original.attemptId, 0, receipt.txid) { beforeDispatch ->
                beforeDispatch()
                broadcasts++
                OnchainSendOutcome.Unknown(receipt.txid)
            }
        }
        assertEquals(0, broadcasts)
        failMarker = false
        val reopened = OnchainSendAttemptStore(testDispatcher, keychain, service, kotlin.time.Clock.System)
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
            "ab".repeat(32),
            listOf(OnchainSendInput("11".repeat(32), 0u)),
            attempt.address,
            attempt.amountSats,
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
            "ab".repeat(32),
            listOf(OnchainSendInput("11".repeat(32), 0u)),
            attempt.address,
            attempt.amountSats,
        )
        val retained = store.retainPreparedReceipt(attempt.attemptId, 0, receipt, false)
        store.broadcastPreparedCandidate(attempt.attemptId, 0, receipt.txid) {
            it()
            OnchainSendOutcome.Unknown(receipt.txid)
        }
        // Even before outcome persistence, this process knows dispatch was attempted.
        assertFailsWith<to.bitkit.utils.ServiceError.PaymentDeadlineExpired> {
            store.broadcastPreparedCandidate(attempt.attemptId, 0, receipt.txid) {
                throw to.bitkit.utils.ServiceError.PaymentDeadlineExpired()
            }
        }
        assertEquals(retained.copy(initialDispatchAttempted = true), store.current())
        val reopened = OnchainSendAttemptStore(testDispatcher, keychain, service, kotlin.time.Clock.System)
        assertFailsWith<to.bitkit.utils.ServiceError.PaymentDeadlineExpired> {
            reopened.broadcastPreparedCandidate(attempt.attemptId, 0, receipt.txid) {
                throw to.bitkit.utils.ServiceError.PaymentDeadlineExpired()
            }
        }
        assertEquals(retained.copy(initialDispatchAttempted = true), reopened.current())
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
    fun `restart releases unsigned ordinary sends and transfers but keeps live preparation`() = test {
        for (isTransfer in listOf(false, true)) {
            var saved: String? = null
            val keychain = mock<Keychain>()
            whenever(keychain.loadString(key, 0)).thenAnswer { saved }
            whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
            whenever(keychain.delete(key, 0)).doSuspendableAnswer { saved = null }
            val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
            val original = store.admit(
                walletId = WalletScope.default,
                requestId = null, orderId = if (isTransfer) "original-order" else null,
                address = "bcrt1qrecipient", amountSats = 1_000uL, isMaxAmount = false,
                feeRateSatsPerVByte = 1uL, isTransfer = isTransfer, channelId = null,
                tags = emptyList(), beforeSendAttempt = {},
            )
            assertEquals(original, store.current(), "Current process must retain its live preparation")
            val reopened = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
            val snapshot = reopened.backupSnapshot(0) { emptyList() }
            assertNull(snapshot.first, "An unsigned abandoned attempt must not stall wallet backup")
            assertNull(reopened.current(), "An unsigned crash guard cannot represent a dispatched transaction")
            assertNull(saved)
            reopened.admitForTest()
        }
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
    fun `unconfirmed original cannot win while a successor is retained`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val backup = lenientJson
            .decodeFromString<to.bitkit.models.WalletBackupV1>(bytes.decodeToString())
        val wire = requireNotNull(requireNotNull(backup.paykitPaymentState).activeOnchainAttempt)
        val original = wire.restored("regtest", wire.wallet.binding, "wallet0", 0)
        val values = mutableMapOf<Int, String>()
        val keychain = mock<Keychain>()
        val service = mock<to.bitkit.services.LightningService>()
        whenever(service.currentWalletIndex).thenReturn(0)
        whenever(keychain.loadString(eq(key), any())).thenAnswer { values[it.getArgument(1)] }
        whenever(keychain.upsertString(eq(key), any(), any())).doSuspendableAnswer {
            values[it.getArgument(2)] = it.getArgument(1)
        }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, service, kotlin.time.Clock.System)
        store.restoreActive(original)
        val first = original.candidateTxids.first()
        val successor = original.candidateTxids.last()
        assertNull(store.observeExactTransaction(first, isConfirmed = false))
        assertEquals(original, store.current())
        val accepted = store.recordOutcome(original.attemptId, OnchainSendOutcome.Accepted(successor), 0)
        assertEquals(successor, accepted.txid)
        assertNull(store.observeExactTransaction(first, isConfirmed = false))
        assertEquals(successor, store.current()?.txid)
    }

    @Test
    fun `restored exact candidate with contact observes without resend and acknowledges`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val backup = lenientJson
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
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val wire = requireNotNull(
            lenientJson
                .decodeFromString<to.bitkit.models.WalletBackupV1>(bytes.decodeToString())
                .paykitPaymentState?.activeOnchainAttempt,
        )
        val accepted = wire.copy(
            status = "accepted",
            candidateFeeRates = mapOf(requireNotNull(wire.txid) to "4"),
            followup = wire.followup?.copy(contact = null)
        )
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
        store.restoreActive(accepted.copy(evidence = OnchainSendEvidence.Unknown))
        assertEquals(completed, store.current())
        assertFalse(requireNotNull(store.current()).isUnresolved)
    }

    @Test
    fun `restored supported accepted context resets acknowledgement and finishes idempotently`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val backup = lenientJson
            .decodeFromString<to.bitkit.models.WalletBackupV1>(bytes.decodeToString())
        val wire = requireNotNull(backup.paykitPaymentState?.activeOnchainAttempt)
        val accepted = wire.copy(
            status = "accepted",
            candidateFeeRates = mapOf(requireNotNull(wire.txid) to "4"),
            followup = wire.followup?.copy(contact = null)
        )
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
    fun `send-all funding validates prepared amount before candidate retention or dispatch`() = test {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        val context = OnchainTransferContext(100_000uL, 100_000uL, 97_000uL, 99_000uL)
        val attempt = store.admit(
            walletId = "wallet-1", requestId = null, orderId = "order-1", address = "bcrt1qfunding",
            amountSats = 99_000uL, isMaxAmount = true, feeRateSatsPerVByte = 1uL, isTransfer = true,
            channelId = null, tags = emptyList(), transferContext = context, beforeSendAttempt = {},
        )
        val receipt = OnchainPreparedReceipt(
            "ab".repeat(32),
            listOf(OnchainSendInput("11".repeat(32), 0u)),
            attempt.address,
            99_500uL,
            miningFeeSats = 500uL,
        )
        for (amount in listOf(98_999uL, 100_001uL)) {
            assertFailsWith<IllegalArgumentException> {
                store.retainPreparedReceipt(attempt.attemptId, 0, receipt.copy(amountSats = amount), false)
            }
            assertNull(store.current()?.txid)
            assertTrue(store.current()?.candidateTxids?.isEmpty() == true)
            var dispatched = false
            assertFailsWith<IllegalArgumentException> {
                store.broadcastPreparedCandidate(attempt.attemptId, 0, receipt.txid) {
                    dispatched = true
                    OnchainSendOutcome.Accepted(receipt.txid)
                }
            }
            assertFalse(dispatched)
        }
        for (fee in listOf(null, 501uL, ULong.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> {
                store.retainPreparedReceipt(attempt.attemptId, 0, receipt.copy(miningFeeSats = fee), false)
            }
            assertNull(store.current()?.txid)
            assertTrue(store.current()?.candidateTxids?.isEmpty() == true)
        }
        val retained = store.retainPreparedReceipt(attempt.attemptId, 0, receipt, false)
        assertEquals(99_500uL, retained.amountSats)
        assertEquals(context, retained.transferContext)
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
        assertFailsWith<IllegalStateException> {
            store.markLocalFollowupComplete(
                attempt.attemptId,
                attempt.walletIndex
            )
        }
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
        val first = async {
            store.admitForTest {
                callbacks++
                entered.complete(Unit)
                finish.await()
            }
        }
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
        val send = launch {
            admitted.complete(store.admitForTest())
            kotlinx.coroutines.awaitCancellation()
        }
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

    @Test
    fun `verified winning fee remains durable after reopening the attempt store`() = test {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        val service = mock<LightningService>()
        val store = OnchainSendAttemptStore(testDispatcher, keychain, service, kotlin.time.Clock.System)
        val attempt = store.admitForTest()
        val txid = "ab".repeat(32)
        store.recordOutcome(attempt.attemptId, OnchainSendOutcome.Accepted(txid), 0)
        store.retainWinningFee(attempt.attemptId, 0, txid, 281uL)
        val reopened = OnchainSendAttemptStore(testDispatcher, keychain, service, kotlin.time.Clock.System)
        val durable = requireNotNull(reopened.current())
        assertEquals(281uL, durable.verifiedWinningFeeSats)
        assertEquals("281", durable.backupFollowup?.feeSats)
        assertEquals(txid, durable.txid)
    }

    @Test
    fun `expired Shop payment keeps its guard until its recorded release is final`() = test {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        whenever(keychain.delete(key, 0)).doSuspendableAnswer { saved = null }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        val attempt = store.admit(
            walletId = WalletScope.default, requestId = PaykitPaymentRequestId("request", "payer"),
            orderId = "order", address = "bcrt1qrecipient", amountSats = 1000uL,
            isMaxAmount = false, feeRateSatsPerVByte = 1uL, isTransfer = false,
            channelId = null, tags = emptyList(), beforeSendAttempt = {}, payerIdentity = "payer",
        )
        val receipt = OnchainPreparedReceipt(
            txid = "ab".repeat(32),
            inputs = listOf(OnchainSendInput("11".repeat(32), 0u)),
            address = attempt.address,
            amountSats = attempt.amountSats,
        )
        store.retainPreparedReceipt(attempt.attemptId, 0, receipt, false)
        val outcome = store.broadcastPreparedCandidate(attempt.attemptId, 0, receipt.txid) { beforeDispatch ->
            beforeDispatch()
            OnchainSendOutcome.Unknown(receipt.txid)
        }
        store.recordOutcome(attempt.attemptId, outcome, 0)
        val unknown = requireNotNull(store.current())
        assertTrue(unknown.canBeAbandoned)
        // The release transaction, not the restore marker, keeps a restored attempt safe to cancel.
        assertTrue(unknown.copy(restoredFromBackup = true).canBeAbandoned)

        val releaseReceipt = OnchainPreparedReceipt(
            txid = "77".repeat(32),
            inputs = receipt.inputs,
            address = "bcrt1qown",
            amountSats = 1_000uL,
            feeRateSatsPerVByte = 3uL,
            miningFeeSats = 423uL,
        )
        assertNull(store.recordRelease(unknown, releaseReceipt), "A release needs the approval first")
        val approved = requireNotNull(store.approveRelease(unknown))
        assertTrue(approved.releaseApproved)
        assertEquals(approved, store.approveRelease(unknown))
        val candidateRelease = releaseReceipt.copy(txid = "ab".repeat(32))
        assertNull(store.recordRelease(approved, candidateRelease), "A candidate cannot be its own release")
        // A restore of the same operation keeps the local approval and its release.
        store.restoreActive(unknown.copy(restoredFromBackup = true))
        val restored = requireNotNull(store.current())
        assertTrue(restored.releaseApproved && restored.canBeAbandoned)
        assertNull(store.recordRelease(restored.copy(amountSats = 999uL), releaseReceipt))
        val recorded = requireNotNull(store.recordRelease(restored, releaseReceipt))
        // The release's rate and fee are saved with it, so a reopened Cancel payment can still outbid it
        assertEquals(releaseReceipt.txid, recorded.releaseTxid)
        assertEquals(3uL, recorded.releaseFeeRateSatsPerVByte)
        assertEquals(423uL, recorded.releaseMiningFeeSats)
        assertEquals(recorded, OnchainSendAttemptStore(testDispatcher, keychain, mock(), Clock.System).current())
        store.restoreActive(unknown.copy(restoredFromBackup = true))
        assertEquals(recorded, store.current())
        val release = OnchainInputSpend("77".repeat(32), receipt.inputs, listOf("bcrt1qown", "bcrt1qchange"), false)
        assertFalse(store.releaseCancelled(recorded.attemptId, 0, release) { true })
        assertFalse(store.releaseCancelled(recorded.attemptId, 0, release.copy(isFinal = true)) { false })
        assertEquals(recorded, store.current())
        assertTrue(store.releaseCancelled(recorded.attemptId, 0, release.copy(isFinal = true)) { true })
        assertNull(store.current())
        assertTrue(store.admitForTest(walletId = WalletScope.default).attemptId != attempt.attemptId)
    }

    @Test
    fun `ordinary payment is never abandoned`() = test {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        val attempt = store.admitForTest(walletId = WalletScope.default)
        store.recordOutcome(attempt.attemptId, OnchainSendOutcome.Unknown("ab".repeat(32)), attempt.walletIndex)
        val unknown = requireNotNull(store.current())

        assertFalse(unknown.canBeAbandoned)
        assertNull(store.approveRelease(unknown))
        assertEquals(unknown, store.current())
    }

    @Test
    fun `refused Blocktank funding can be released once its order can no longer be retried`() = test {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        whenever(keychain.delete(key, 0)).doSuspendableAnswer { saved = null }
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        val attempt = store.admit(
            walletId = WalletScope.default, requestId = null, orderId = "order-1", address = "bcrt1qfunding",
            amountSats = 50_000uL, isMaxAmount = false, feeRateSatsPerVByte = 1uL, isTransfer = true,
            channelId = null, tags = emptyList(),
            transferContext = OnchainTransferContext(txTotalSats = 50_200uL, preTransferOnchainSats = 100_000uL),
            beforeSendAttempt = {},
        )
        val receipt = OnchainPreparedReceipt(
            "ab".repeat(32),
            listOf(OnchainSendInput("11".repeat(32), 0u)),
            attempt.address,
            attempt.amountSats,
            miningFeeSats = 200uL,
        )
        store.retainPreparedReceipt(attempt.attemptId, 0, receipt, false)
        val outcome = store.broadcastPreparedCandidate(attempt.attemptId, 0, receipt.txid) { beforeDispatch ->
            beforeDispatch()
            OnchainSendOutcome.Rejected(receipt.txid, "min relay fee not met")
        }
        store.recordOutcome(attempt.attemptId, outcome, 0)
        val refused = requireNotNull(store.current())

        // Retry original is refused while transfers are unavailable, so release is the only exit.
        assertTrue(refused.blocksNextSend)
        assertTrue(refused.canBeAbandoned, "A refused Blocktank funding attempt must be releasable")
    }

    @Test
    fun `a release saved before its fee was kept reads with an unknown fee`() = test {
        val keychain = mock<Keychain>()
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), Clock.System)
        val released = OnchainSendAttempt(
            walletId = WalletScope.default, attemptId = "attempt",
            requestId = PaykitPaymentRequestId("request", "payee"), orderId = null, address = "bcrt1qrecipient",
            amountSats = 1_000uL, isMaxAmount = false, feeRateSatsPerVByte = 1uL, isTransfer = false,
            channelId = null, tags = emptyList(), evidence = OnchainSendEvidence.Unknown, txid = "ab".repeat(32),
            originalInputs = listOf(OnchainSendInput("11".repeat(32), 0u)), candidateTxids = listOf("ab".repeat(32)),
            payerIdentity = "payer", releaseApproved = true, releaseTxid = "77".repeat(32),
        )
        // As an app that predates the release's fee saved it
        val saved = Json.encodeToString(OnchainSendAttempt.serializer(), released)
        assertTrue(saved.contains("releaseTxid") && !saved.contains("releaseMiningFeeSats"))
        whenever(keychain.loadString(key, 0)).thenReturn(saved)

        val current = requireNotNull(store.current())

        assertEquals(released, current)
        assertNull(current.releaseFeeRateSatsPerVByte)
        assertNull(current.releaseMiningFeeSats)
    }

    @Test
    fun `final non-payment spend of an original input releases an unresolvable request or order guard`() = test {
        for (orderBound in listOf(false, true)) {
            val keychain = inMemoryKeychain()
            val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), Clock.System)
            val unresolved = store.dispatchedForTest(orderBound, OnchainSendOutcome.Rejected(firstTxid, "fee too low"))
            assertFailsWith<OnchainSendBlockedError> { store.admitForTest(walletId = WalletScope.default) }
            var removedProof: OnchainSendAttempt? = null

            val released = store.releaseCancelled(unresolved.attemptId, 0, finalCancellation) {
                removedProof = it
                true
            }

            assertTrue(released)
            assertEquals(unresolved, removedProof)
            assertNull(store.current())
            store.admitForTest(walletId = WalletScope.default)
        }
    }

    @Test
    fun `spends a candidate could still beat or that may pay the recipient never release the guard`() = test {
        val store = OnchainSendAttemptStore(testDispatcher, inMemoryKeychain(), mock(), Clock.System)
        val unresolved = store.dispatchedForTest(outcome = OnchainSendOutcome.Unknown(firstTxid))
        val keptSpends = listOf(
            finalCancellation.copy(isFinal = false),
            finalCancellation.copy(txid = firstTxid.uppercase()),
            finalCancellation.copy(outputAddresses = listOf("BCRT1QRECIPIENT", "bcrt1qchange")),
            finalCancellation.copy(outputAddresses = listOf(null, "bcrt1qchange")),
            finalCancellation.copy(outputAddresses = emptyList()),
            finalCancellation.copy(inputs = listOf(OnchainSendInput("33".repeat(32), 0u))),
        )

        for (spend in keptSpends) {
            assertFalse(store.releaseCancelled(unresolved.attemptId, 0, spend) { error("proof must stay") })
            assertEquals(unresolved, store.current())
        }
        assertFalse(store.releaseCancelled("other-attempt", 0, finalCancellation) { error("proof must stay") })
        assertFalse(store.releaseCancelled(unresolved.attemptId, 0, finalCancellation) { false })
        assertEquals(unresolved, store.current())

        val observed = requireNotNull(store.observeExactTransaction(firstTxid))
        assertFalse(store.releaseCancelled(unresolved.attemptId, 0, finalCancellation) { error("proof must stay") })
        assertEquals(observed, store.current())
        assertTrue(observed.blocksNextSend)
    }

    @Test
    fun `cancellation dispatches only while no candidate has positive evidence and keeps the guard`() = test {
        val store = OnchainSendAttemptStore(testDispatcher, inMemoryKeychain(), mock(), Clock.System)
        val unresolved = store.dispatchedForTest(outcome = OnchainSendOutcome.Unknown(firstTxid))
        var broadcasts = 0
        val cancel: suspend () -> OnchainSendOutcome = {
            broadcasts++
            OnchainSendOutcome.Accepted(finalCancellation.txid)
        }

        assertEquals(
            OnchainSendOutcome.Accepted(finalCancellation.txid),
            store.broadcastCancellation(
                unresolved.attemptId,
                0,
                cancelReceipt,
                cancel,
            )
        )
        val sent = requireNotNull(store.current())
        val expected = unresolved.copy(
            cancellationTxid = finalCancellation.txid,
            cancellationFeeRateSatsPerVByte = 3uL,
            cancellationMiningFeeSats = 400uL,
        )
        assertEquals(expected, sent)
        assertTrue(sent.blocksNextSend)
        assertFailsWith<OnchainSendBlockedError> {
            store.broadcastCancellation(unresolved.attemptId, 1, cancelReceipt, cancel)
        }
        assertFailsWith<OnchainSendBlockedError> {
            store.broadcastCancellation("other-attempt", 0, cancelReceipt, cancel)
        }
        store.observeExactTransaction(firstTxid)
        assertFailsWith<OnchainSendBlockedError> {
            store.broadcastCancellation(unresolved.attemptId, 0, cancelReceipt, cancel)
        }
        assertEquals(1, broadcasts)

        val liveStore = OnchainSendAttemptStore(testDispatcher, inMemoryKeychain(), mock(), Clock.System)
        val live = liveStore.admitForTest(walletId = WalletScope.default)
        liveStore.retainPreparedReceipt(
            live.attemptId,
            0,
            OnchainPreparedReceipt(firstTxid, originalInputs, live.address, live.amountSats),
            false,
        )
        assertFailsWith<OnchainSendBlockedError> {
            liveStore.broadcastCancellation(live.attemptId, 0, cancelReceipt, cancel)
        }
        assertEquals(1, broadcasts)
    }

    @Test
    fun `a Cancel payment is saved before its broadcast and survives a restart`() = test {
        val keychain = inMemoryKeychain()
        val store = OnchainSendAttemptStore(testDispatcher, keychain, mock(), Clock.System)
        val unresolved = store.dispatchedForTest(outcome = OnchainSendOutcome.Unknown(firstTxid))
        var savedAtBroadcast: OnchainSendAttempt? = null

        val outcome = store.broadcastCancellation(unresolved.attemptId, 0, cancelReceipt) {
            savedAtBroadcast = OnchainSendAttemptStore(testDispatcher, keychain, mock(), Clock.System).current()
            OnchainSendOutcome.Unknown(finalCancellation.txid)
        }

        assertEquals(OnchainSendOutcome.Unknown(finalCancellation.txid), outcome)
        val reopened = requireNotNull(OnchainSendAttemptStore(testDispatcher, keychain, mock(), Clock.System).current())
        assertEquals(reopened, savedAtBroadcast)
        assertEquals(finalCancellation.txid, reopened.cancellationTxid)
        assertEquals(3uL, reopened.cancellationFeeRateSatsPerVByte)
        assertEquals(400uL, reopened.cancellationMiningFeeSats)
        assertTrue(reopened.hasSentCancellation)
        assertEquals(400uL, reopened.highestSpendFee)
        assertEquals(3uL, reopened.highestSpendFeeRate)
    }

    @Test
    fun `a refused Cancel payment leaves the earlier one saved`() = test {
        val store = OnchainSendAttemptStore(testDispatcher, inMemoryKeychain(), mock(), Clock.System)
        val unresolved = store.dispatchedForTest(outcome = OnchainSendOutcome.Unknown(firstTxid))
        store.broadcastCancellation(unresolved.attemptId, 0, cancelReceipt) {
            OnchainSendOutcome.Accepted(finalCancellation.txid)
        }
        val accepted = requireNotNull(store.current())
        val higher = cancelReceipt.copy(txid = "12".repeat(32), feeRateSatsPerVByte = 9uL, miningFeeSats = 900uL)

        val refused = store.broadcastCancellation(unresolved.attemptId, 0, higher) {
            OnchainSendOutcome.Rejected(higher.txid, "insufficient fee")
        }
        assertIs<OnchainSendOutcome.Rejected>(refused)
        assertEquals(accepted, store.current())

        // A failure that may have dispatched keeps the newer record, as it may be in a mempool
        assertFailsWith<IllegalStateException> {
            store.broadcastCancellation(unresolved.attemptId, 0, higher) { error("connection lost") }
        }
        assertEquals(higher.txid, store.current()?.cancellationTxid)
        assertEquals(9uL, store.current()?.cancellationFeeRateSatsPerVByte)
        assertFailsWith<IllegalArgumentException> {
            store.broadcastCancellation(unresolved.attemptId, 0, cancelReceipt.copy(txid = firstTxid)) {
                error("a candidate is not a cancellation")
            }
        }
    }

    @Test
    fun `a sped up cancellation replaces the saved one and keeps the highest fee`() = test {
        val store = OnchainSendAttemptStore(testDispatcher, inMemoryKeychain(), mock(), Clock.System)
        val unresolved = store.dispatchedForTest(outcome = OnchainSendOutcome.Unknown(firstTxid))
        store.broadcastCancellation(unresolved.attemptId, 0, cancelReceipt) {
            OnchainSendOutcome.Accepted(finalCancellation.txid)
        }
        val bumped = "34".repeat(32)

        assertEquals(bumped, store.bumpCancellation(unresolved.attemptId, 0, 7uL) { bumped.uppercase() })

        val current = requireNotNull(store.current())
        assertEquals(bumped, current.cancellationTxid)
        assertEquals(7uL, current.cancellationFeeRateSatsPerVByte)
        assertEquals(400uL, current.cancellationMiningFeeSats)
        assertFailsWith<OnchainSendBlockedError> { store.bumpCancellation("other-attempt", 0, 8uL) { bumped } }
        store.observeExactTransaction(firstTxid)
        assertFailsWith<OnchainSendBlockedError> {
            store.bumpCancellation(unresolved.attemptId, 0, 8uL) { error("a paid attempt is not bumped") }
        }
    }

    private val firstTxid = "ab".repeat(32)
    private val cancelReceipt = OnchainPreparedReceipt(
        txid = "ef".repeat(32),
        inputs = listOf(OnchainSendInput("11".repeat(32), 0u), OnchainSendInput("22".repeat(32), 1u)),
        address = "bcrt1qown",
        amountSats = 546uL,
        feeRateSatsPerVByte = 3uL,
        miningFeeSats = 400uL,
    )
    private val originalInputs = listOf(OnchainSendInput("11".repeat(32), 0u), OnchainSendInput("22".repeat(32), 1u))
    private val finalCancellation = OnchainInputSpend(
        txid = "ef".repeat(32),
        inputs = listOf(OnchainSendInput("22".repeat(32), 1u)),
        outputAddresses = listOf("bcrt1qown", "bcrt1qchange"),
        isFinal = true,
    )

    private fun inMemoryKeychain(): Keychain {
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever { keychain.upsertString(eq(key), any(), eq(0)) }.doSuspendableAnswer { saved = it.getArgument(1) }
        whenever { keychain.delete(key, 0) }.doSuspendableAnswer { saved = null }
        return keychain
    }

    private suspend fun OnchainSendAttemptStore.dispatchedForTest(
        orderBound: Boolean = false,
        outcome: OnchainSendOutcome,
    ): OnchainSendAttempt {
        val attempt = admit(
            walletId = WalletScope.default,
            requestId = PaykitPaymentRequestId("request", "payee").takeUnless { orderBound },
            orderId = "order".takeIf { orderBound },
            address = "bcrt1qrecipient",
            amountSats = 1_000uL,
            isMaxAmount = false,
            feeRateSatsPerVByte = 1uL,
            isTransfer = orderBound,
            channelId = null,
            tags = emptyList(),
            transferContext = OnchainTransferContext(1_100uL, 2_000uL, 900uL, 1_000uL).takeIf { orderBound },
            beforeSendAttempt = {},
            payerIdentity = "payer".takeUnless { orderBound },
        )
        val receipt = OnchainPreparedReceipt(firstTxid, originalInputs, attempt.address, attempt.amountSats)
        retainPreparedReceipt(attempt.attemptId, 0, receipt, false)
        broadcastPreparedCandidate(attempt.attemptId, 0, firstTxid) { beforeDispatch ->
            beforeDispatch()
            outcome
        }
        return recordOutcome(attempt.attemptId, outcome, 0)
    }

    private suspend fun OnchainSendAttemptStore.admitForTest(
        walletId: String = "wallet-1",
        contactPublicKey: String? = null,
        beforeSendAttempt: suspend () -> Unit = {
        }
    ): OnchainSendAttempt = admit(
        walletId = walletId,
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
