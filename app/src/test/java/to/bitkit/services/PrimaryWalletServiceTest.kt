package to.bitkit.services

import com.synonym.bitkitcore.OnchainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.OutPoint
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.serializers.SettingsSerializer
import to.bitkit.ext.createChannelDetails
import to.bitkit.models.PrimaryNode
import to.bitkit.repositories.OnchainSendAttemptStore
import to.bitkit.repositories.OnchainSendNotDispatchedError
import to.bitkit.repositories.OnchainSendOutcome
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import to.bitkit.utils.ServiceError
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PrimaryWalletServiceTest : BaseUnitTest() {
    companion object {
        private const val KEY = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        private const val CHANNEL = "channel-1"
        private const val USER_CHANNEL = "42"
        private val OLD_TX = "11".repeat(32)
        private val NEW_TX = "22".repeat(32)
    }

    private val nodeService = mock<PrimaryWalletNodeService>()
    private val keychain = mock<Keychain>()
    private val onchainAttempts = mock<OnchainSendAttemptStore>()
    private val coreService = mock<CoreService>()
    private val settingsStore = mock<SettingsStore>()
    private val settings = MutableStateFlow(SettingsData(primaryNodeUri = "$KEY@127.0.0.1:9735"))
    private val saved = mutableMapOf<Int, String>()
    private val savedFundingHistory = mutableMapOf<Int, String>()
    private lateinit var store: PrimaryWalletOperationStore
    private lateinit var service: PrimaryWalletService

    @Before
    fun setUp() {
        whenever(nodeService.walletIndex).thenReturn(0)
        whenever(settingsStore.data).thenReturn(settings)
        whenever { settingsStore.update(any()) }.doSuspendableAnswer { call ->
            settings.update(call.getArgument(0))
        }
        whenever { nodeService.failureAcknowledgementState(any()) }
            .thenReturn(PrimaryFailureAcknowledgementState("node-instance-1", null))
        whenever(keychain.loadString(eq(Keychain.Key.PRIMARY_WALLET_OPERATION.name), any())).thenAnswer {
            saved[it.getArgument(1)]
        }
        whenever { keychain.upsertString(eq(Keychain.Key.PRIMARY_WALLET_OPERATION.name), any(), any()) }
            .doSuspendableAnswer {
                saved[it.getArgument(2)] = it.getArgument(1)
            }
        whenever(keychain.loadString(eq(Keychain.Key.PRIMARY_WALLET_FUNDING_TRANSACTIONS.name), any())).thenAnswer {
            savedFundingHistory[it.getArgument(1)]
        }
        whenever { keychain.upsertString(eq(Keychain.Key.PRIMARY_WALLET_FUNDING_TRANSACTIONS.name), any(), any()) }
            .doSuspendableAnswer {
                savedFundingHistory[it.getArgument(2)] = it.getArgument(1)
            }
        store = PrimaryWalletOperationStore(keychain)
        service = PrimaryWalletService(
            nodeService,
            store,
            onchainAttempts,
            coreService,
            settingsStore,
            testDispatcher,
        )
    }

    private fun home() = createChannelDetails().copy(
        channelId = CHANNEL,
        userChannelId = USER_CHANNEL,
        counterpartyNodeId = KEY,
        fundingTxo = OutPoint(OLD_TX, 0u),
        isChannelReady = true,
        isUsable = true,
        outboundCapacityMsat = 100_000_000uL,
    )

    private fun snapshot(hasHome: Boolean = true) = PrimaryWalletSnapshot(
        walletIndex = 0,
        primary = PrimaryNode(KEY, "127.0.0.1:9735"),
        isConnected = true,
        confirmedSats = 30_000uL,
        confirmedInputCount = 1,
        fundingFeeRate = 1uL,
        channels = if (hasHome) listOf(home()) else emptyList(),
        hasPendingFunding = false,
    )

    private fun operation(kind: PrimaryWalletOperationKind = PrimaryWalletOperationKind.SpliceIn) =
        PrimaryWalletOperation(
            id = "operation-1",
            walletIndex = 0,
            walletId = "wallet0",
            primaryNodeId = KEY,
            amountSats = 29_400uL,
            kind = kind,
            stage = PrimaryWalletOperationStage.Dispatched,
            userChannelId = USER_CHANNEL,
            previousFundingTxid = OLD_TX,
            address = if (kind == PrimaryWalletOperationKind.SpliceOut) "bcrt1qrecipient" else null,
        )

    private fun pendingEvent(nodeId: String = KEY, userChannel: String = USER_CHANNEL) = Event.SplicePending(
        channelId = CHANNEL,
        userChannelId = userChannel,
        counterpartyNodeId = nodeId,
        newFundingTxo = OutPoint(NEW_TX, 0u),
    )

    @Test
    fun `explicit primary close persists pause before native close and across restart`() = test {
        service.closeConnection(home()) { assertTrue(settings.value.primaryFundingPaused) }
        val output = ByteArrayOutputStream()
        SettingsSerializer.writeTo(settings.value, output)
        settings.value = SettingsSerializer.readFrom(output.toByteArray().inputStream())
        val restored = PrimaryWalletService(
            nodeService,
            PrimaryWalletOperationStore(keychain),
            onchainAttempts,
            coreService,
            settingsStore,
            testDispatcher,
        )
        whenever(nodeService.snapshot()).thenReturn(snapshot(hasHome = false))

        assertTrue(restored.fundIfNeeded().isSuccess)
        assertTrue(settings.value.primaryFundingPaused)
        verify(nodeService, never()).dispatch(any(), any())
    }

    @Test
    fun `automatic funding waits for explicit close and remains paused after it completes`() = test {
        val closing = CompletableDeferred<Unit>()
        val finishClose = CompletableDeferred<Unit>()
        val close = async {
            service.closeConnection(home()) {
                closing.complete(Unit)
                finishClose.await()
            }
        }
        closing.await()
        whenever(nodeService.snapshot()).thenReturn(snapshot(hasHome = false))
        val funding = async { service.fundIfNeeded() }
        advanceUntilIdle()
        assertFalse(funding.isCompleted)
        finishClose.complete(Unit)
        close.await()

        assertTrue(funding.await().isSuccess)
        verify(nodeService, never()).dispatch(any(), any())
    }

    @Test
    fun `definitive failure before native dispatch restores previous funding pause`() = test {
        for (previousPause in listOf(false, true)) {
            settings.value = settings.value.copy(primaryFundingPaused = previousPause)
            val failure = runCatching {
                service.closeConnection(home()) { throw ServiceError.NodeNotStarted() }
            }.exceptionOrNull()

            assertIs<ServiceError.NodeNotStarted>(failure)
            assertEquals(previousPause, settings.value.primaryFundingPaused)
        }
    }

    @Test
    fun `ambiguous close failure retains pause and pending outgoing proof`() = test {
        val pending = operation(PrimaryWalletOperationKind.SpliceOut).copy(fundingTxid = NEW_TX)
        store.update(0) { pending }

        val failure = runCatching {
            service.closeConnection(home()) { throw AppError("Close response was lost") }
        }.exceptionOrNull()

        assertIs<AppError>(failure)
        assertTrue(settings.value.primaryFundingPaused)
        assertEquals(pending, store.load(0))
    }

    @Test
    fun `cancellation after starting close retains durable funding pause`() = test {
        val closing = CompletableDeferred<Unit>()
        val close = async {
            service.closeConnection(home()) {
                closing.complete(Unit)
                awaitCancellation()
            }
        }
        closing.await()

        close.cancelAndJoin()

        assertTrue(settings.value.primaryFundingPaused)
    }

    @Test
    fun `pause persistence failure prevents native close`() = test {
        whenever(settingsStore.update(any())).doSuspendableAnswer { throw AppError("Storage unavailable") }
        var closeInvoked = false

        assertTrue(runCatching { service.closeConnection(home()) { closeInvoked = true } }.isFailure)

        assertFalse(closeInvoked)
    }

    @Test
    fun `closing another peer does not pause primary funding`() = test {
        var closeInvoked = false

        service.closeConnection(home().copy(counterpartyNodeId = "another-peer")) { closeInvoked = true }

        assertTrue(closeInvoked)
        assertFalse(settings.value.primaryFundingPaused)
        verify(settingsStore, never()).update(any())
    }

    @Test
    fun `resuming funding permits a new channel without clearing existing journal`() = test {
        service.closeConnection(home()) {}
        settings.value = settings.value.copy(primaryFundingPaused = false)
        whenever(nodeService.snapshot()).thenReturn(snapshot(hasHome = false))
        whenever(nodeService.dispatch(any(), any())).doSuspendableAnswer {
            requireNotNull(store.load(0)?.userChannelId)
        }

        assertTrue(service.fundIfNeeded().isSuccess)

        assertEquals(PrimaryWalletOperationKind.Open, store.load(0)?.kind)
        verify(nodeService).dispatch(any(), any())
    }

    @Test
    fun `funding requires confirmed threshold and stays below five percent estimated fee`() {
        assertNull(service.fundingPlan(snapshot().copy(confirmedSats = 24_999uL)))
        assertNull(service.fundingPlan(snapshot().copy(confirmedInputCount = 0)))
        assertNull(service.fundingPlan(snapshot().copy(fundingFeeRate = 3uL)))
        assertEquals(29_400uL, service.fundingPlan(snapshot())?.amountSats)
        assertEquals(PrimaryWalletOperationKind.Open, service.fundingPlan(snapshot(false))?.kind)
        assertEquals(PrimaryWalletOperationKind.SpliceIn, service.fundingPlan(snapshot())?.kind)
    }

    @Test
    fun `funding does not choose among multiple channels or use a channel still opening`() {
        assertNull(service.fundingPlan(snapshot().copy(channels = listOf(home(), home().copy(userChannelId = "43")))))
        assertNull(service.fundingPlan(snapshot().copy(channels = listOf(home().copy(isChannelReady = false)))))
    }

    @Test
    fun `funding writes dispatch intent before native mutation and never duplicates a pending operation`() = test {
        whenever(nodeService.snapshot()).thenReturn(snapshot())
        whenever(nodeService.dispatch(any(), any())).doSuspendableAnswer {
            val durable = requireNotNull(store.load(0))
            assertEquals(PrimaryWalletOperationStage.Dispatched, durable.stage)
            assertEquals(PrimaryWalletOperationKind.SpliceIn, durable.kind)
            USER_CHANNEL
        }

        assertTrue(service.fundIfNeeded().isSuccess)
        assertIs<PrimaryWalletPendingError>(service.fundIfNeeded().exceptionOrNull())
        verify(nodeService, times(1)).dispatch(any(), any())
    }

    @Test
    fun `ambiguous dispatch failure stays blocked after reopening persistent storage`() = test {
        whenever(nodeService.snapshot()).thenReturn(snapshot())
        whenever(nodeService.dispatch(any(), any())).doSuspendableAnswer {
            throw AppError("Connection lost after dispatch")
        }

        assertTrue(service.fundIfNeeded().isFailure)
        val reopened = PrimaryWalletOperationStore(keychain)
        val restored = PrimaryWalletService(
            nodeService,
            reopened,
            onchainAttempts,
            coreService,
            settingsStore,
            testDispatcher,
        )
        assertTrue(requireNotNull(restored.currentOperation()).blocksWallet)
        assertIs<PrimaryWalletPendingError>(restored.fundIfNeeded().exceptionOrNull())
        verify(nodeService, times(1)).dispatch(any(), any())
    }

    @Test
    fun `Iroh listener preparation failure cannot leave a funding operation permanently dispatched`() = test {
        val lightning = mock<LightningService>()
        val native = mock<Node>()
        whenever(lightning.node).thenReturn(native)
        whenever(lightning.primaryPeerAddress(any())).thenAnswer { throw IOException("Listener unavailable") }
        val dispatch = PrimaryWalletNodeService(lightning, settingsStore)
        whenever(nodeService.snapshot()).thenReturn(snapshot(hasHome = false))
        whenever(nodeService.dispatch(any(), any())).doSuspendableAnswer {
            dispatch.dispatch(it.getArgument(0), it.getArgument(1))
        }

        assertIs<PrimaryWalletDispatchRejected>(service.fundIfNeeded().exceptionOrNull())
        assertEquals(PrimaryWalletOperationStage.Failed, store.load(0)?.stage)
        assertFalse(requireNotNull(store.load(0)).blocksWallet)
        verifyNoInteractions(native)
    }

    @Test
    fun `native rejection before accepting an operation allows later retry`() = test {
        whenever(nodeService.snapshot()).thenReturn(snapshot())
        whenever(nodeService.dispatch(any(), any())).doSuspendableAnswer {
            throw PrimaryWalletDispatchRejected("Insufficient funds")
        }

        assertTrue(service.fundIfNeeded().isFailure)
        assertFalse(requireNotNull(store.load(0)).blocksWallet)
        whenever(nodeService.dispatch(any(), any())).thenReturn(USER_CHANNEL)
        assertTrue(service.fundIfNeeded().isSuccess)
        verify(nodeService, times(2)).dispatch(any(), any())
    }

    @Test
    fun `events for another channel or primary do not complete our operation`() = test {
        store.update(0) { operation() }

        service.handleEvent(pendingEvent(userChannel = "99"))
        service.handleEvent(pendingEvent(nodeId = "different-primary"))

        assertEquals(PrimaryWalletOperationStage.Dispatched, store.load(0)?.stage)
        assertNull(store.load(0)?.fundingTxid)
    }

    @Test
    fun `pending event is persisted before channel ready releases funding gate`() = test {
        store.update(0) { operation() }
        service.handleEvent(pendingEvent())

        assertEquals(NEW_TX, store.load(0)?.fundingTxid)
        assertTrue(requireNotNull(store.load(0)).blocksWallet)

        service.handleEvent(Event.ChannelReady(CHANNEL, USER_CHANNEL, KEY, OutPoint(NEW_TX, 0u)))

        assertEquals(PrimaryWalletOperationStage.Ready, store.load(0)?.stage)
        assertFalse(requireNotNull(store.load(0)).blocksWallet)
    }

    @Test
    fun `old channel ready and abandoned broadcast do not erase uncertainty`() = test {
        store.update(0) { operation() }
        service.handleEvent(Event.ChannelReady(CHANNEL, USER_CHANNEL, KEY, OutPoint(OLD_TX, 0u)))
        assertEquals(PrimaryWalletOperationStage.Dispatched, store.load(0)?.stage)

        service.handleEvent(pendingEvent())
        service.handleEvent(Event.SpliceFailed(CHANNEL, USER_CHANNEL, KEY, OutPoint(NEW_TX, 0u)))

        assertTrue(requireNotNull(store.load(0)).blocksWallet)
        assertEquals(NEW_TX, store.load(0)?.fundingTxid)
    }

    @Test
    fun `terminal splice failure cannot release funding gate before native acknowledgement`() = test {
        val failure = Event.SpliceFailed(CHANNEL, USER_CHANNEL, KEY, null)
        store.update(0) { operation() }
        whenever(nodeService.snapshot()).thenReturn(snapshot())
        whenever(nodeService.failureAcknowledgementState(0)).thenReturn(
            PrimaryFailureAcknowledgementState("node-instance-1", primaryFailureEventFingerprint(failure)),
        )

        service.handleEvent(failure)
        service.onEventAcknowledged(Event.SpliceFailed(CHANNEL, "unrelated", KEY, null))

        assertEquals(PrimaryWalletOperationStage.FailureAwaitingAck, store.load(0)?.stage)
        assertTrue(requireNotNull(store.load(0)).blocksWallet)
        assertIs<PrimaryWalletPendingError>(service.fundIfNeeded().exceptionOrNull())
        verify(nodeService, never()).dispatch(any(), any())

        service.onEventAcknowledged(failure)

        assertEquals(PrimaryWalletOperationStage.Failed, store.load(0)?.stage)
        assertFalse(requireNotNull(store.load(0)).blocksWallet)
    }

    @Test
    fun `only a reloaded node with an advanced persisted queue can infer terminal acknowledgement`() = test {
        val failure = Event.SpliceFailed(CHANNEL, USER_CHANNEL, KEY, null)
        store.update(0) { operation() }
        service.handleEvent(failure)
        val reopened = PrimaryWalletService(
            nodeService,
            PrimaryWalletOperationStore(keychain),
            onchainAttempts,
            coreService,
            settingsStore,
            testDispatcher,
        )
        whenever(nodeService.snapshot()).thenReturn(snapshot().copy(confirmedSats = 0uL))
        // Native eventHandled may pop its live queue and then fail persistence. That same instance cannot release us.
        whenever(nodeService.failureAcknowledgementState(0)).thenReturn(
            PrimaryFailureAcknowledgementState("node-instance-1", null),
        )

        assertIs<PrimaryWalletPendingError>(reopened.fundIfNeeded().exceptionOrNull())
        assertTrue(requireNotNull(reopened.currentOperation()).blocksWallet)

        // Reloading the native node reveals that the failed acknowledgement left the original event on disk.
        whenever(nodeService.failureAcknowledgementState(0)).thenReturn(
            PrimaryFailureAcknowledgementState("node-instance-2", primaryFailureEventFingerprint(failure)),
        )
        assertIs<PrimaryWalletPendingError>(reopened.fundIfNeeded().exceptionOrNull())
        assertTrue(requireNotNull(reopened.currentOperation()).blocksWallet)

        // A fresh load with a different head covers successful acknowledgement followed by a lost callback.
        whenever(nodeService.failureAcknowledgementState(0)).thenReturn(
            PrimaryFailureAcknowledgementState("node-instance-3", null),
        )

        assertTrue(reopened.fundIfNeeded().isSuccess)
        assertEquals(PrimaryWalletOperationStage.Failed, reopened.currentOperation()?.stage)
        verify(nodeService, never()).dispatch(any(), any())
    }

    @Test
    fun `stale reload evidence cannot overwrite a failure observed on the new native instance`() = test {
        val failure = Event.SpliceFailed(CHANNEL, USER_CHANNEL, KEY, null)
        store.update(0) { operation() }
        service.handleEvent(failure)
        whenever(nodeService.snapshot()).thenReturn(snapshot().copy(confirmedSats = 0uL))
        whenever(nodeService.failureAcknowledgementState(0)).doSuspendableAnswer {
            // The event handler has persisted a failure from the reloaded instance while reconciliation awaited it.
            store.update(0) { requireNotNull(it).copy(failureNodeInstanceId = "node-instance-2") }
            PrimaryFailureAcknowledgementState("node-instance-2", null)
        }

        assertIs<PrimaryWalletPendingError>(service.fundIfNeeded().exceptionOrNull())

        assertEquals("node-instance-2", store.load(0)?.failureNodeInstanceId)
        assertEquals(PrimaryWalletOperationStage.FailureAwaitingAck, store.load(0)?.stage)
        assertTrue(requireNotNull(store.load(0)).blocksWallet)
        verify(nodeService, never()).dispatch(any(), any())
    }

    @Test
    fun `funded channel closure releases automatic funding only after native acknowledgement`() = test {
        val closed = Event.ChannelClosed(CHANNEL, USER_CHANNEL, KEY, null)
        store.update(0) {
            operation(PrimaryWalletOperationKind.Open).copy(
                fundingTxid = NEW_TX,
                stage = PrimaryWalletOperationStage.Submitted,
            )
        }

        service.handleEvent(closed)

        assertEquals(PrimaryWalletOperationStage.FailureAwaitingAck, store.load(0)?.stage)
        assertTrue(requireNotNull(store.load(0)).blocksWallet)

        service.onEventAcknowledged(closed)

        assertEquals(PrimaryWalletOperationStage.Closed, store.load(0)?.stage)
        assertFalse(requireNotNull(store.load(0)).blocksWallet)
    }

    @Test
    fun `closed outgoing splice retains uncertainty until exact payment receipt is saved`() = test {
        val closed = Event.ChannelClosed(CHANNEL, USER_CHANNEL, KEY, null)
        store.update(0) {
            operation(PrimaryWalletOperationKind.SpliceOut).copy(
                fundingTxid = NEW_TX,
                stage = PrimaryWalletOperationStage.Submitted,
            )
        }
        whenever(nodeService.matchesPayment(any())).thenReturn(false)

        service.handleEvent(closed)
        service.onEventAcknowledged(closed)

        assertEquals(PrimaryWalletOperationStage.Closed, store.load(0)?.stage)
        assertTrue(requireNotNull(store.load(0)).blocksWallet)
        whenever(nodeService.snapshot()).thenReturn(snapshot(false))
        assertIs<PrimaryWalletPendingError>(service.fundIfNeeded().exceptionOrNull())
        verify(nodeService, never()).dispatch(any(), any())
    }

    @Test
    fun `open persists its native channel id before dispatch and matches closure after restart`() = test {
        whenever(nodeService.snapshot()).thenReturn(snapshot(false))
        whenever(nodeService.dispatch(any(), any())).doSuspendableAnswer {
            val operation = it.getArgument<PrimaryWalletOperation>(0)
            val nativeId = requireNotNull(operation.userChannelId)
            assertTrue(nativeId.toBigInteger().signum() > 0)
            assertEquals(nativeId, store.load(0)?.userChannelId)
            throw AppError("Process interrupted after native open")
        }

        assertTrue(service.fundIfNeeded().isFailure)
        val persistedId = requireNotNull(store.load(0)?.userChannelId)
        val closed = Event.ChannelClosed(CHANNEL, persistedId, KEY, null)
        val reopened = PrimaryWalletService(
            nodeService,
            PrimaryWalletOperationStore(keychain),
            onchainAttempts,
            coreService,
            settingsStore,
            testDispatcher,
        )

        reopened.handleEvent(closed)
        assertTrue(requireNotNull(reopened.currentOperation()).blocksWallet)
        reopened.onEventAcknowledged(closed)

        assertEquals(PrimaryWalletOperationStage.Closed, reopened.currentOperation()?.stage)
        assertFalse(requireNotNull(reopened.currentOperation()).blocksWallet)
        verify(nodeService, times(1)).dispatch(any(), any())
    }

    @Test
    fun `restart binds an outbound open that crashed before returned channel id was saved`() = test {
        store.update(0) {
            operation(PrimaryWalletOperationKind.Open).copy(userChannelId = null, previousFundingTxid = null)
        }
        whenever(nodeService.snapshot()).thenReturn(
            snapshot().copy(
                confirmedSats = 0uL,
                channels = listOf(
                    home().copy(isOutbound = true, channelValueSats = 29_400uL, fundingTxo = OutPoint(NEW_TX, 0u)),
                ),
            ),
        )

        assertTrue(service.fundIfNeeded().isSuccess)

        assertEquals(USER_CHANNEL, store.load(0)?.userChannelId)
        assertEquals(PrimaryWalletOperationStage.Ready, store.load(0)?.stage)
        assertFalse(requireNotNull(store.load(0)).blocksWallet)
        verify(nodeService, never()).dispatch(any(), any())
    }

    @Test
    fun `restart never binds unsolicited inbound channel to interrupted outbound open`() = test {
        store.update(0) {
            operation(PrimaryWalletOperationKind.Open).copy(userChannelId = null, previousFundingTxid = null)
        }
        whenever(nodeService.snapshot()).thenReturn(
            snapshot().copy(channels = listOf(home().copy(isOutbound = false, channelValueSats = 29_400uL))),
        )

        assertIs<PrimaryWalletPendingError>(service.fundIfNeeded().exceptionOrNull())

        assertNull(store.load(0)?.userChannelId)
        verify(nodeService, never()).dispatch(any(), any())
    }

    @Test
    fun `a payment that timed out cannot be sent again after restart`() = test {
        whenever(nodeService.snapshot()).thenReturn(snapshot())
        whenever(nodeService.dispatch(any(), any())).thenReturn(USER_CHANNEL)
        val result = service.sendOnchain("bcrt1qrecipient", 10_000uL, 1uL)

        assertIs<PrimaryWalletPendingError>(result.exceptionOrNull())
        val reopened = PrimaryWalletService(
            nodeService,
            PrimaryWalletOperationStore(keychain),
            onchainAttempts,
            coreService,
            settingsStore,
            testDispatcher,
        )
        assertIs<PrimaryWalletPendingError>(reopened.sendOnchain("bcrt1qrecipient", 10_000uL, 1uL).exceptionOrNull())
        verify(nodeService, times(1)).dispatch(any(), any())
    }

    @Test
    fun `unverified outgoing transaction returns unknown and keeps the gate closed`() = test {
        whenever(nodeService.snapshot()).thenReturn(snapshot())
        whenever(nodeService.dispatch(any(), any())).thenReturn(USER_CHANNEL)
        whenever(nodeService.matchesPayment(any())).thenReturn(false)
        val send = async { service.sendOnchain("bcrt1qrecipient", 10_000uL, 1uL) }
        service.handleEvent(pendingEvent())

        assertEquals(OnchainSendOutcome.Unknown(NEW_TX), send.await().getOrThrow())
        service.handleEvent(Event.ChannelReady(CHANNEL, USER_CHANNEL, KEY, OutPoint(NEW_TX, 0u)))
        assertTrue(requireNotNull(store.load(0)).blocksWallet)
        assertFalse(requireNotNull(store.load(0)).localFollowupComplete)
    }

    @Test
    fun `outgoing ready event retains gate until exact receipt is durable`() = test {
        val activity = mock<ActivityService>()
        val receipt = mock<OnchainActivity>()
        whenever(coreService.activity).thenReturn(activity)
        whenever(nodeService.matchesPayment(any())).thenReturn(true)
        whenever(nodeService.paymentFee(any())).thenReturn(215uL)
        whenever(receipt.address).thenReturn("bcrt1qrecipient")
        whenever(receipt.value).thenReturn(29_400uL)
        whenever(receipt.fee).thenReturn(215uL)
        whenever(activity.getOnchainActivityByTxId(NEW_TX, "wallet0")).thenReturn(null)
        store.update(0) { operation(PrimaryWalletOperationKind.SpliceOut).copy(feeRate = 1uL) }

        service.handleEvent(pendingEvent())
        service.handleEvent(Event.ChannelReady(CHANNEL, USER_CHANNEL, KEY, OutPoint(NEW_TX, 0u)))

        assertTrue(requireNotNull(store.load(0)).blocksWallet)
        whenever(activity.getOnchainActivityByTxId(NEW_TX, "wallet0")).thenReturn(receipt)
        service.handleEvent(Event.ChannelReady(CHANNEL, USER_CHANNEL, KEY, OutPoint(NEW_TX, 0u)))

        assertTrue(requireNotNull(store.load(0)).localFollowupComplete)
        assertFalse(requireNotNull(store.load(0)).blocksWallet)
        verify(activity, times(3)).createSentOnchainActivityFromSendResult(
            txid = NEW_TX,
            address = "bcrt1qrecipient",
            amount = 29_400uL,
            fee = 215uL,
            feeRate = 1uL,
            isTransfer = false,
            channelId = null,
            walletId = "wallet0",
        )
    }

    @Test
    fun `outgoing rejection is known not dispatched and retains reviewed fee rate`() = test {
        whenever(nodeService.snapshot()).thenReturn(snapshot().copy(fundingFeeRate = 10uL))
        whenever(nodeService.dispatch(any(), any())).doSuspendableAnswer {
            val intent = it.getArgument<PrimaryWalletOperation>(0)
            assertEquals(2uL, intent.feeRate)
            assertEquals(listOf("rent"), intent.tags)
            throw PrimaryWalletDispatchRejected("Native rejected fee rate")
        }

        assertEquals(600uL, service.estimateSpliceOutFee(10_000uL, 2uL))
        val result = service.sendOnchain("bcrt1qrecipient", 10_000uL, 2uL, tags = listOf("rent", "rent"))

        assertIs<OnchainSendNotDispatchedError>(result.exceptionOrNull())
        assertFalse(requireNotNull(store.load(0)).blocksWallet)
    }

    @Test
    fun `paused funding still reconciles outgoing tags before releasing the gate after restart`() = test {
        settings.value = settings.value.copy(primaryFundingPaused = true)
        val activity = mock<ActivityService>()
        val receipt = mock<OnchainActivity>()
        whenever(coreService.activity).thenReturn(activity)
        whenever(nodeService.matchesPayment(any())).thenReturn(true)
        whenever(nodeService.paymentFee(any())).thenReturn(215uL)
        whenever(nodeService.snapshot()).thenReturn(
            snapshot().copy(channels = listOf(home().copy(fundingTxo = OutPoint(NEW_TX, 0u)))),
        )
        whenever(receipt.id).thenReturn("receipt")
        whenever(receipt.address).thenReturn("bcrt1qrecipient")
        whenever(receipt.value).thenReturn(29_400uL)
        whenever(receipt.fee).thenReturn(215uL)
        whenever(activity.getOnchainActivityByTxId(NEW_TX, "wallet0")).thenReturn(receipt)
        whenever(activity.appendTags("receipt", listOf("rent"), "wallet0")).thenReturn(Result.success(Unit))
        whenever(activity.tags("receipt", "wallet0")).thenReturn(emptyList())
        store.update(0) {
            operation(PrimaryWalletOperationKind.SpliceOut).copy(
                stage = PrimaryWalletOperationStage.Ready,
                fundingTxid = NEW_TX,
                tags = listOf("rent"),
            )
        }

        val reopened = PrimaryWalletService(
            nodeService,
            PrimaryWalletOperationStore(keychain),
            onchainAttempts,
            coreService,
            settingsStore,
            testDispatcher,
        )
        assertIs<PrimaryWalletPendingError>(reopened.fundIfNeeded().exceptionOrNull())
        assertTrue(requireNotNull(reopened.currentOperation()).blocksWallet)
        whenever(activity.tags("receipt", "wallet0")).thenReturn(listOf("rent"))
        whenever(nodeService.snapshot()).thenReturn(
            snapshot().copy(
                confirmedSats = 0uL,
                channels = listOf(home().copy(fundingTxo = OutPoint(NEW_TX, 0u))),
            ),
        )

        assertTrue(reopened.fundIfNeeded().isSuccess)
        assertFalse(requireNotNull(reopened.currentOperation()).blocksWallet)
        assertTrue(requireNotNull(reopened.currentOperation()).localFollowupComplete)
        verify(nodeService, never()).dispatch(any(), any())
    }

    @Test
    fun `pre-dispatch callback failure never calls native mutation`() = test {
        whenever(nodeService.snapshot()).thenReturn(snapshot())
        val result = service.sendOnchain("bcrt1qrecipient", 10_000uL, 1uL) {
            throw AppError("Review changed")
        }

        assertTrue(result.isFailure)
        verify(nodeService, never()).dispatch(any(), any())
        assertFalse(requireNotNull(store.load(0)).blocksWallet)
    }

    @Test
    fun `funding activity marker survives journal replacement and excludes external splice payments`() = test {
        store.update(0) {
            operation().copy(stage = PrimaryWalletOperationStage.Ready, fundingTxid = NEW_TX)
        }
        store.update(0) {
            operation(PrimaryWalletOperationKind.SpliceOut).copy(
                id = "later-external-payment",
                stage = PrimaryWalletOperationStage.Ready,
                fundingTxid = OLD_TX,
                localFollowupComplete = true,
            )
        }

        val reopened = PrimaryWalletOperationStore(keychain)

        assertTrue(reopened.isFundingTransaction(0, "wallet0", NEW_TX))
        assertFalse(reopened.isFundingTransaction(0, "wallet0", OLD_TX))
        assertFalse(reopened.isFundingTransaction(0, "hardware-wallet", NEW_TX))
        assertFalse(reopened.isFundingTransaction(1, "wallet0", NEW_TX))
    }

    @Test
    fun `corrupted durable state blocks instead of being reset`() = test {
        saved[0] = "not valid json"
        whenever(nodeService.snapshot()).thenReturn(snapshot())

        assertTrue(service.fundIfNeeded().isFailure)
        verify(nodeService, never()).dispatch(any(), any())
    }

    @Test
    fun `an operation lock serializes direct sends with background funding`() = test {
        whenever(nodeService.snapshot()).thenReturn(snapshot())
        whenever(nodeService.dispatch(any(), any())).thenReturn(USER_CHANNEL)
        var started = false
        val release = CompletableDeferred<Unit>()
        val direct = async {
            service.withWalletOperation {
                started = true
                release.await()
            }
        }
        val funding = async { service.fundIfNeeded() }
        assertTrue(started)
        verify(nodeService, never()).dispatch(any(), any())
        release.complete(Unit)
        direct.await()
        advanceUntilIdle()
        assertTrue(funding.await().isSuccess)
        assertNotNull(store.load(0))
    }
}
