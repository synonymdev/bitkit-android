package to.bitkit.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.NodeException
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsStore
import to.bitkit.data.backup.VssStoreIdProvider
import to.bitkit.data.keychain.Keychain
import to.bitkit.ext.createChannelDetails
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.LoggerLdk
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val VERIFY_TIMEOUT_MS = 2_000L

class LightningServiceTest : BaseUnitTest() {
    private val keychain = mock<Keychain>()
    private val vssStoreIdProvider = mock<VssStoreIdProvider>()
    private val settingsStore = mock<SettingsStore>()
    private val loggerLdk = mock<LoggerLdk>()
    private val node = mock<Node>()

    private lateinit var sut: LightningService

    @Before
    fun setUp() {
        sut = LightningService(
            bgDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            keychain = keychain,
            vssStoreIdProvider = vssStoreIdProvider,
            settingsStore = settingsStore,
            loggerLdk = loggerLdk,
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
        whenever(node.stop()).thenThrow(NodeException.NotRunning("not running"))

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

    // Regression: a failing node stop must still release the handle instead of rethrowing and leaking it
    @Test
    fun `stop destroys the node handle when node stop throws`() = test {
        whenever(node.stop()).thenThrow(NodeException.ConnectionFailed("boom"))

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
}
