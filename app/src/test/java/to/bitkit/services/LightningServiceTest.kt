package to.bitkit.services

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.NodeException
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
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
