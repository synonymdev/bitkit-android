package to.bitkit.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.lightningdevkit.ldknode.AddressType
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.OnchainPayment
import org.lightningdevkit.ldknode.OnchainWalletAccount
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.async.newSingleThreadDispatcher
import to.bitkit.data.SettingsStore
import to.bitkit.data.WatchOnlyAccountReconciliationState
import to.bitkit.data.WatchOnlyAccountStore
import to.bitkit.data.backup.VssStoreIdProvider
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.ext.createChannelDetails
import to.bitkit.models.WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.models.WatchOnlyAccountSetupState
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.LoggerLdk
import to.bitkit.utils.ServiceError
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private const val VERIFY_TIMEOUT_MS = 2_000L
private const val RELEASE_GATE_PROBE_MS = 200L
private const val STOP_BOUND_MS = 5_000L
private val SHORT_GATE_TIMEOUT = 200.milliseconds

class LightningServiceTest : BaseUnitTest() {
    private val keychain = mock<Keychain>()
    private val vssStoreIdProvider = mock<VssStoreIdProvider>()
    private val settingsStore = mock<SettingsStore>()
    private val watchOnlyAccountStore = mock<WatchOnlyAccountStore>()
    private val loggerLdk = mock<LoggerLdk>()
    private val node = mock<Node>()
    private val watchOnlyAccountLifecycleCoordinator = WatchOnlyAccountLifecycleCoordinator()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var sut: LightningService

    @Before
    fun setUp() {
        sut = LightningService(
            bgDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            keychain = keychain,
            vssStoreIdProvider = vssStoreIdProvider,
            settingsStore = settingsStore,
            watchOnlyAccountStore = watchOnlyAccountStore,
            loggerLdk = loggerLdk,
            watchOnlyAccountLifecycleCoordinator = watchOnlyAccountLifecycleCoordinator,
        )
        sut.node = node
    }

    @Test
    fun `canReceive returns false when channel is ready but not usable`() {
        val readyButNotUsable = createChannelDetails().copy(
            isChannelReady = true,
            isUsable = false,
        )
        whenever(node.listChannels()).thenReturn(listOf(readyButNotUsable))

        assertFalse(sut.canReceive())
    }

    @Test
    fun `canReceive returns true when channel is usable`() {
        val usableChannel = createChannelDetails().copy(
            isChannelReady = true,
            isUsable = true,
        )
        whenever(node.listChannels()).thenReturn(listOf(usableChannel))

        assertTrue(sut.canReceive())
    }

    @Test
    fun `stop destroys the node handle and clears it`() = test {
        sut.stop()

        verify(node).stop()
        verify(node).destroy()
        assertNull(sut.node)
    }

    @Test
    fun `stop destroys the node handle when it is already not running`() = test {
        whenever(node.stop()).thenThrow(NodeException.NotRunning())

        sut.stop()

        verify(node).destroy()
        assertNull(sut.node)
    }

    @Test
    fun `stop is a no-op when no node is set`() = test {
        sut.node = null

        sut.stop()

        verify(node, never()).destroy()
    }

    // Regression: a cancelled caller must not abandon teardown, leaving the rust node to the GC finalizer
    @Test
    fun `stop completes teardown when the caller is cancelled`() = test {
        val job = launch { sut.stop() }

        job.cancelAndJoin()

        verify(node).destroy()
        assertNull(sut.node)
    }

    // Regression: cancelling while the listener is still winding down must not skip native teardown
    @Test
    fun `stop completes teardown when cancelled during listener cleanup`() = test {
        val listenerEntered = CompletableDeferred<Unit>()
        val listenerCleanup = CompletableDeferred<Unit>()
        whenever(node.nextEventAsync()).doSuspendableAnswer {
            listenerEntered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { listenerCleanup.await() }
            }
        }
        sut.startEventListener()
        listenerEntered.await()

        val stopJob = launch { sut.stop() }
        stopJob.cancel()
        listenerCleanup.complete(Unit)
        stopJob.join()
        testScheduler.advanceUntilIdle()

        verify(node).stop()
        verify(node).destroy()
        assertNull(sut.node)
    }

    // Regression: an external stop (not from inside a handler) must cancel AND join the listener, so
    // the loop has fully exited before teardown — the counterpart to the handler-triggered skip.
    @Test
    fun `external stop cancels and joins the running listener`() = test {
        val listening = CompletableDeferred<Unit>()
        val listenerExited = CompletableDeferred<Unit>()
        whenever(node.nextEventAsync()).doSuspendableAnswer {
            listening.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                listenerExited.complete(Unit)
            }
        }
        sut.startEventListener()
        listening.await() // listener is parked inside nextEventAsync

        sut.stop()

        // stop() returned, so cancelAndJoin completed: the listener has exited, not been left running.
        assertTrue(listenerExited.isCompleted)
        verify(node).destroy()
    }

    // Regression: a stop requested from inside an event handler runs on the listener job, so joining
    // that job would deadlock; teardown must still complete
    @Test
    fun `stop from within an event handler completes teardown without deadlock`() = test {
        val event = Event.PaymentSuccessful(null, "hash", null, null)
        // Gate the first event so startEventListener returns and assigns listenerJob before the
        // handler runs; otherwise the eager test dispatcher would hide the self-join.
        val releaseEvent = CompletableDeferred<Event>()
        var delivered = false
        whenever(node.nextEventAsync()).doSuspendableAnswer {
            // A second poll would be on the node destroy() already freed; the loop must exit instead.
            check(!delivered) { "polled the node after teardown freed it" }
            delivered = true
            releaseEvent.await()
        }
        // The handler stops the node from inside the listener job; the loop then exits on the flag.
        val handler: NodeEventHandler = { sut.stop() }

        sut.startEventListener(handler)
        releaseEvent.complete(event)
        testScheduler.advanceUntilIdle()

        // timeout() polls in real time: teardown finishes on the LDK/IO threads, not virtual time.
        // Without the self-join guard, stop() would deadlock and node.stop() would never run.
        verify(node, timeout(VERIFY_TIMEOUT_MS)).stop()
        verify(node, timeout(VERIFY_TIMEOUT_MS)).destroy()
        // The loop re-checks its guard after the handler returns instead of polling the freed node.
        verify(node, times(1)).nextEventAsync()
    }

    // Regression: startEventListener() also joins listenerJob, so a re-arm requested from inside a
    // handler (e.g. LightningRepo.start on an already-running node) would self-cancel the listener
    // it runs on — its CancellationException swallowed by runCatching — silently killing it. The
    // listener must survive the re-arm and keep delivering events.
    @Test
    fun `startEventListener from within a handler keeps the listener running`() = test {
        // Gate the first event so the outer startEventListener returns and assigns listenerJob before
        // the handler re-arms; otherwise the eager test dispatcher leaves listenerJob null and hides it.
        val firstEvent = CompletableDeferred<Event>()
        val secondEvent = CompletableDeferred<Event>()
        var polls = 0
        whenever(node.nextEventAsync()).doSuspendableAnswer {
            if (++polls == 1) firstEvent.await() else secondEvent.await()
        }
        var handlerCalls = 0
        val handler: NodeEventHandler = {
            when (++handlerCalls) {
                1 -> sut.startEventListener { } // re-arm from inside the listener; must not kill it
                2 -> sut.stop() // reached only if the listener survived; also terminates the loop
            }
        }

        sut.startEventListener(handler)
        firstEvent.complete(Event.PaymentSuccessful(null, "h1", null, null))
        secondEvent.complete(Event.PaymentSuccessful(null, "h2", null, null))
        testScheduler.advanceUntilIdle()

        // Reached only if the second event was delivered, i.e. the re-arm did not kill the listener.
        verify(node, timeout(VERIFY_TIMEOUT_MS)).stop()
        verify(node, timeout(VERIFY_TIMEOUT_MS)).destroy()
    }

    // Regression: stop() nulls listenerJob while the old loop is still unwinding, so a racing start()
    // can install a new node and re-arm shouldListenForEvents before the old loop returns. The loop
    // must key on node identity and exit, not poll the node the previous teardown freed.
    @Test
    fun `listener stops polling the old node once a new node is swapped in`() = test {
        val newNode = mock<Node>()
        val releaseEvent = CompletableDeferred<Event>()
        var delivered = false
        whenever(node.nextEventAsync()).doSuspendableAnswer {
            check(!delivered) { "polled the stale node after it was swapped out" }
            delivered = true
            releaseEvent.await()
        }
        // Simulate a concurrent start(): swap in a new node with the listener flag still on.
        val handler: NodeEventHandler = { sut.node = newNode }

        sut.startEventListener(handler)
        releaseEvent.complete(Event.PaymentSuccessful(null, "hash", null, null))
        testScheduler.advanceUntilIdle()

        verify(node, times(1)).nextEventAsync()
        verify(newNode, never()).nextEventAsync()
    }

    private class GateFixture(
        scope: CoroutineScope,
        val gated: LightningService,
        val destroyGate: CountDownLatch,
        val destroyed: AtomicBoolean,
    ) : CoroutineScope by scope

    // Runs [block] against a LightningService on real dispatchers whose node.destroy() blocks on
    // destroyGate, so the release gate can be observed. Real dispatchers + a controllably delayed
    // destroy() are exactly the setup the gate needs to be tested independently of virtual time.
    private fun runGateTest(block: suspend GateFixture.() -> Unit) = runBlocking {
        Env.initAppStoragePath(tempFolder.root.absolutePath)
        val io = newSingleThreadDispatcher("test-gate-io")
        val bg = newSingleThreadDispatcher("test-gate-bg")
        val gated = LightningService(
            bg,
            io,
            keychain,
            vssStoreIdProvider,
            settingsStore,
            watchOnlyAccountStore,
            loggerLdk,
            watchOnlyAccountLifecycleCoordinator,
        )
        gated.node = node
        val destroyGate = CountDownLatch(1)
        val destroyed = AtomicBoolean(false)
        whenever(node.destroy()).thenAnswer {
            destroyGate.await()
            destroyed.set(true)
        }
        try {
            GateFixture(this, gated, destroyGate, destroyed).block()
        } finally {
            destroyGate.countDown() // release any wedged destroy so the io thread can exit
            io.close()
            bg.close()
        }
    }

    // Regression: destructive storage work must wait for the previous node's release (free_node) so
    // native lifetimes never overlap. Also pins (d): stop() returns within its bound despite the wedge.
    @Test
    fun `resetNetworkGraph waits for the previous node release to finish`() = runGateTest {
        withTimeout(STOP_BOUND_MS) { gated.stop() } // stop returns within its bound though destroy() is wedged

        val resetDone = CompletableDeferred<Unit>()
        launch(Dispatchers.Default) {
            gated.resetNetworkGraph(walletIndex = 0)
            resetDone.complete(Unit)
        }

        delay(RELEASE_GATE_PROBE_MS)
        assertFalse(resetDone.isCompleted) // gate holds while the release is still draining
        assertFalse(destroyed.get())

        destroyGate.countDown()
        resetDone.await()
        assertTrue(destroyed.get()) // release finished before resetNetworkGraph proceeded
    }

    // Regression (b): wipeStorage deletes storage and must wait for the release just like the rebuild.
    @Test
    fun `wipeStorage waits for the previous node release to finish`() = runGateTest {
        withTimeout(STOP_BOUND_MS) { gated.stop() }

        val wipeDone = CompletableDeferred<Unit>()
        launch(Dispatchers.Default) {
            gated.wipeStorage(walletIndex = 0)
            wipeDone.complete(Unit)
        }

        delay(RELEASE_GATE_PROBE_MS)
        assertFalse(wipeDone.isCompleted)
        assertFalse(destroyed.get())

        destroyGate.countDown()
        wipeDone.await()
        assertTrue(destroyed.get())
    }

    // Regression (a): the rebuild path (setup) must wait for the release before building a new node.
    @Test
    fun `setup waits for the previous node release before rebuilding`() = runGateTest {
        withTimeout(STOP_BOUND_MS) { gated.stop() }

        val setupDone = CompletableDeferred<Result<Unit>>()
        launch(Dispatchers.Default) {
            setupDone.complete(runCatching { gated.setup(walletIndex = 0) })
        }

        delay(RELEASE_GATE_PROBE_MS)
        assertFalse(setupDone.isCompleted) // gate blocks the rebuild while the release drains

        destroyGate.countDown()
        setupDone.await() // proceeds once released (build then fails on the mocked deps, which is fine)
    }

    // Regression (c): with no pending release the gate must add no latency.
    @Test
    fun `wipeStorage proceeds immediately when no release is pending`() = runGateTest {
        gated.node = null // no stop() has run, so releaseJob is null

        withTimeout(RELEASE_GATE_PROBE_MS) { gated.wipeStorage(walletIndex = 0) } // completes without waiting
    }

    // Regression: a stuck free_node must not block rebuilds forever; the gate throws once bounded out.
    @Test
    fun `awaitNodeRelease throws when the release never finishes`() = runGateTest {
        withTimeout(STOP_BOUND_MS) { gated.stop() } // release launched; destroy() stays wedged (never counted down)

        val error = runCatching { gated.awaitNodeRelease(timeout = SHORT_GATE_TIMEOUT) }.exceptionOrNull()

        assertTrue(error is ServiceError.NodeReleaseTimeout)
    }

    // Regression: a failing node stop must still release the handle instead of rethrowing and leaking it
    @Test
    fun `stop destroys the node handle when node stop throws`() = test {
        whenever(node.stop()).thenThrow(NodeException.ConnectionFailed())

        sut.stop()

        verify(node).destroy()
        assertNull(sut.node)
    }

    // Regression: destroying twice would surface later as IllegalStateException from callWithPointer
    @Test
    fun `consecutive stop calls destroy the handle only once`() = test {
        sut.stop()
        sut.stop()

        verify(node, times(1)).destroy()
    }

    @Test
    fun `startup config includes enabled active and authorizing accounts for current wallet`() {
        val enabled = watchOnlyAccount(accountIndex = 1, walletIndex = 0, enabled = true)
        val disabled = watchOnlyAccount(accountIndex = 2, walletIndex = 0, enabled = false)
        val otherWallet = watchOnlyAccount(accountIndex = 3, walletIndex = 1, enabled = true)
        val pending = watchOnlyAccount(accountIndex = 4, walletIndex = 0, enabled = true)
            .copy(setupState = WatchOnlyAccountSetupState.PendingDelivery)
        val authorizing = watchOnlyAccount(accountIndex = 5, walletIndex = 0, enabled = true)
            .copy(setupState = WatchOnlyAccountSetupState.Authorizing)

        val configs = enabledOnchainWalletAccountConfigs(
            records = listOf(enabled, disabled, otherWallet, pending, authorizing),
            walletIndex = 0,
        )

        assertEquals(listOf(1u, 5u), configs.map { it.accountIndex })
        assertEquals(listOf(enabled.xpub, authorizing.xpub), configs.map { it.xpub })
    }

    @Test
    fun `reconciliation pre-reveals index 999 for an already tracked authorizing account`() = test {
        val account = watchOnlyAccount(accountIndex = 5, walletIndex = 0, enabled = true)
            .copy(setupState = WatchOnlyAccountSetupState.Authorizing)
        val onchainPayment = mock<OnchainPayment>()
        val status = mock<NodeStatus>()
        whenever(node.listOnchainWalletAccounts()).thenReturn(
            listOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 5u))
        )
        whenever(node.onchainPayment()).thenReturn(onchainPayment)
        whenever(node.status()).thenReturn(status)
        whenever(status.isRunning).thenReturn(true)

        whenever(watchOnlyAccountStore.loadReconciliationState()).thenReturn(
            WatchOnlyAccountReconciliationState(listOf(account), emptyList()),
        )

        sut.reconcileWatchOnlyAccounts()

        verify(node, never()).addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 5u, account.xpub)
        verify(onchainPayment).revealReceiveAddressesToAccount(
            AddressType.NATIVE_SEGWIT,
            5u,
            WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX.toUInt(),
        )
        verify(node).syncWallets()
    }

    @Test
    fun `wallet sync retries watch-only reconciliation and syncs once`() = test {
        val account = watchOnlyAccount(accountIndex = 5, walletIndex = 0, enabled = true)
            .copy(setupState = WatchOnlyAccountSetupState.Authorizing)
        val onchainPayment = mock<OnchainPayment>()
        whenever(watchOnlyAccountStore.loadReconciliationState()).thenReturn(
            WatchOnlyAccountReconciliationState(listOf(account), emptyList()),
        )
        whenever(node.listOnchainWalletAccounts()).thenReturn(
            listOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 5u))
        )
        whenever(node.onchainPayment()).thenReturn(onchainPayment)

        sut.sync()

        verify(onchainPayment).revealReceiveAddressesToAccount(
            AddressType.NATIVE_SEGWIT,
            5u,
            WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX.toUInt(),
        )
        verify(node, times(1)).syncWallets()
    }

    @Test
    fun `restore reconciliation unloads replaced account and loads restored account`() = test {
        val replacedAccount = watchOnlyAccount(accountIndex = 1, walletIndex = 0, enabled = true)
        val restoredAccount = watchOnlyAccount(accountIndex = 2, walletIndex = 0, enabled = true)
        val trackedAccounts = mutableListOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u))
        val onchainPayment = mock<OnchainPayment>()
        whenever(watchOnlyAccountStore.loadReconciliationState()).thenReturn(
            WatchOnlyAccountReconciliationState(
                accounts = listOf(restoredAccount),
                accountsPendingRemoval = listOf(replacedAccount),
            ),
        )
        whenever(node.listOnchainWalletAccounts()).thenAnswer { trackedAccounts.toList() }
        whenever(node.onchainPayment()).thenReturn(onchainPayment)
        doAnswer {
            trackedAccounts.remove(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u))
            Unit
        }
            .whenever(node).removeOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)
        doAnswer { trackedAccounts += OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 2u) }
            .whenever(node).addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 2u, restoredAccount.xpub)

        sut.reconcileWatchOnlyAccounts(syncAfterReconcile = false)

        assertEquals(listOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 2u)), trackedAccounts)
        verify(node).removeOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)
        verify(node).addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 2u, restoredAccount.xpub)
        verify(watchOnlyAccountStore).completeReconciliation(walletIndex = 0)
    }

    @Test
    fun `failed restore reconciliation retains removals for the next retry`() = test {
        val replacedAccount = watchOnlyAccount(accountIndex = 1, walletIndex = 0, enabled = true)
        val restoredAccount = watchOnlyAccount(accountIndex = 2, walletIndex = 0, enabled = true)
        val trackedAccounts = mutableListOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u))
        var failAdd = true
        val onchainPayment = mock<OnchainPayment>()
        whenever(watchOnlyAccountStore.loadReconciliationState()).thenReturn(
            WatchOnlyAccountReconciliationState(
                accounts = listOf(restoredAccount),
                accountsPendingRemoval = listOf(replacedAccount),
            ),
        )
        whenever(node.listOnchainWalletAccounts()).thenAnswer { trackedAccounts.toList() }
        whenever(node.onchainPayment()).thenReturn(onchainPayment)
        doAnswer {
            trackedAccounts.remove(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u))
            Unit
        }
            .whenever(node).removeOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 1u)
        doAnswer {
            check(!failAdd) { "add failed" }
            trackedAccounts += OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 2u)
        }.whenever(node).addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, 2u, restoredAccount.xpub)

        assertTrue(runCatching { sut.reconcileWatchOnlyAccounts(syncAfterReconcile = false) }.isFailure)
        verify(watchOnlyAccountStore, never()).completeReconciliation(walletIndex = 0)

        failAdd = false
        sut.reconcileWatchOnlyAccounts(syncAfterReconcile = false)

        assertEquals(listOf(OnchainWalletAccount(AddressType.NATIVE_SEGWIT, 2u)), trackedAccounts)
        verify(watchOnlyAccountStore).completeReconciliation(walletIndex = 0)
    }

    private fun watchOnlyAccount(accountIndex: Int, walletIndex: Int, enabled: Boolean) = WatchOnlyAccountRecord(
        id = "account-$accountIndex",
        walletIndex = walletIndex,
        accountIndex = accountIndex,
        addressType = "nativeSegwit",
        xpub = "xpub-$accountIndex",
        requestFingerprint = "request-$accountIndex",
        createdAt = 1,
        name = "Account $accountIndex",
        isTrackingEnabled = enabled,
        setupState = WatchOnlyAccountSetupState.Active,
    )
}
