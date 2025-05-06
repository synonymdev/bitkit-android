package to.bitkit.repositories

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentId
import org.lightningdevkit.ldknode.Txid
import org.lightningdevkit.ldknode.UserChannelId
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.LnPeer
import to.bitkit.models.NodeLifecycleState
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.services.LightningService
import to.bitkit.services.NodeEventHandler
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LightningRepoTest : BaseUnitTest() {

    private lateinit var sut: LightningRepo

    private val lightningService: LightningService = mock()
    private val ldkNodeEventBus: LdkNodeEventBus = mock()

    @Before
    fun setUp() {
        sut = LightningRepo(
            bgDispatcher = testDispatcher,
            lightningService = lightningService,
            ldkNodeEventBus = ldkNodeEventBus
        )
    }

    private suspend fun startNodeForTesting() {
        sut.setInitNodeLifecycleState()
        whenever(lightningService.node).thenReturn(mock())
        whenever(lightningService.setup(any())).thenReturn(Unit)
        whenever(lightningService.start(anyOrNull(), any())).thenReturn(Unit)
        sut.start().let { assertTrue(it.isSuccess) }
    }

    @Test
    fun `setup should call service setup and return success`() = test {
        whenever(lightningService.setup(any())).thenReturn(Unit)

        val result = sut.setup(0)

        assertTrue(result.isSuccess)
        verify(lightningService).setup(0)
    }

    @Test
    fun `start should transition through correct states`() = test {
        sut.setInitNodeLifecycleState()
        whenever(lightningService.node).thenReturn(mock())
        whenever(lightningService.setup(any())).thenReturn(Unit)
        whenever(lightningService.start(anyOrNull(), any())).thenReturn(Unit)

        sut.lightningState.test {
            assertEquals(NodeLifecycleState.Initializing, awaitItem().nodeLifecycleState)

            sut.start()

            assertEquals(NodeLifecycleState.Starting, awaitItem().nodeLifecycleState)
            assertEquals(NodeLifecycleState.Running, awaitItem().nodeLifecycleState)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stop should transition to stopped state`() = test {
        startNodeForTesting()

        sut.lightningState.test {
            // Verify initial state is Running (from startNodeForTesting)
            assertEquals(NodeLifecycleState.Running, awaitItem().nodeLifecycleState)

            sut.stop()

            assertEquals(NodeLifecycleState.Stopping, awaitItem().nodeLifecycleState)
            assertEquals(NodeLifecycleState.Stopped, awaitItem().nodeLifecycleState)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `newAddress should fail when node is not running`() = test {
        val result = sut.newAddress()
        assertTrue(result.isFailure)
    }

    @Test
    fun `newAddress should succeed when node is running`() = test {
        startNodeForTesting()
        val testAddress = "test_address"
        whenever(lightningService.newAddress()).thenReturn(testAddress)

        val result = sut.newAddress()
        assertTrue(result.isSuccess)
        assertEquals(testAddress, result.getOrNull())
    }

    @Test
    fun `createInvoice should fail when node is not running`() = test {
        val result = sut.createInvoice(description = "test")
        assertTrue(result.isFailure)
    }

    @Test
    fun `payInvoice should fail when node is not running`() = test {
        val result = sut.payInvoice("bolt11", 1000uL)
        assertTrue(result.isFailure)
    }

    @Test
    fun `getPayments should fail when node is not running`() = test {
        val result = sut.getPayments()
        assertTrue(result.isFailure)
    }

    @Test
    fun `openChannel should fail when node is not running`() = test {
        val testPeer = LnPeer("nodeId", "host", "9735")
        val result = sut.openChannel(testPeer, 100000uL)
        assertTrue(result.isFailure)
    }

    @Test
    fun `getNodeId should return null when node is not running`() = test {
        assertNull(sut.getNodeId())
    }

    @Test
    fun `getNodeId should return value when node is running`() = test {
        startNodeForTesting()
        val testNodeId = "test_node_id"
        whenever(lightningService.nodeId).thenReturn(testNodeId)

        assertEquals(testNodeId, sut.getNodeId())
    }

    @Test
    fun `getBalances should return null when node is not running`() = test {
        assertNull(sut.getBalances())
    }

    @Test
    fun `hasChannels should return false when node is not running`() = test {
        assertFalse(sut.hasChannels())
    }

    @Test
    fun `getSyncFlow should return flow from service`() = test {
        val testFlow = flowOf(Unit)
        whenever(lightningService.syncFlow()).thenReturn(testFlow)

        assertEquals(testFlow, sut.getSyncFlow())
    }

    @Test
    fun `syncState should update state with current values`() = test {
        startNodeForTesting()
        val testNodeId = "test_node_id"
        val testStatus = mock<NodeStatus>()
        val testPeers = listOf(mock<LnPeer>())
        val testChannels = listOf(mock<ChannelDetails>())

        whenever(lightningService.nodeId).thenReturn(testNodeId)
        whenever(lightningService.status).thenReturn(testStatus)
        whenever(lightningService.peers).thenReturn(testPeers)
        whenever(lightningService.channels).thenReturn(testChannels)

        sut.syncState()

        assertEquals(testNodeId, sut.lightningState.value.nodeId)
        assertEquals(testStatus, sut.lightningState.value.nodeStatus)
        assertEquals(testPeers, sut.lightningState.value.peers)
        assertEquals(testChannels, sut.lightningState.value.channels)
    }

    @Test
    fun `canSend should return false when node is not running`() = test {
        assertFalse(sut.canSend(1000uL))
    }

    @Test
    fun `wipeStorage should stop node and call service wipe`() = test {
        startNodeForTesting()
        whenever(lightningService.stop()).thenReturn(Unit)

        val result = sut.wipeStorage(0)

        assertTrue(result.isSuccess)
        verify(lightningService).stop()
        verify(lightningService).wipeStorage(0)
    }

    @Test
    fun `connectToTrustedPeers should fail when node is not running`() = test {
        val result = sut.connectToTrustedPeers()
        assertTrue(result.isFailure)
    }

    @Test
    fun `disconnectPeer should fail when node is not running`() = test {
        val testPeer = LnPeer("nodeId", "host", "9735")
        val result = sut.disconnectPeer(testPeer)
        assertTrue(result.isFailure)
    }

    @Test
    fun `sendOnChain should fail when node is not running`() = test {
        val result = sut.sendOnChain("address", 1000uL)
        assertTrue(result.isFailure)
    }
}
