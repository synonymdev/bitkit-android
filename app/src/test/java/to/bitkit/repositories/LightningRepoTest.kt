package to.bitkit.repositories

import app.cash.turbine.test
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.PaymentDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.dto.TransactionMetadata
import to.bitkit.data.keychain.Keychain
import to.bitkit.ext.createChannelDetails
import to.bitkit.models.ElectrumServer
import to.bitkit.models.LnPeer
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.TransactionSpeed
import to.bitkit.services.BlocktankNotificationsService
import to.bitkit.services.CoreService
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.services.LightningService
import to.bitkit.services.LnurlService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LightningRepoTest : BaseUnitTest() {

    private lateinit var sut: LightningRepo

    private val lightningService: LightningService = mock()
    private val ldkNodeEventBus: LdkNodeEventBus = mock()
    private val settingsStore: SettingsStore = mock()
    private val coreService: CoreService = mock()
    private val blocktankNotificationsService: BlocktankNotificationsService = mock()
    private val firebaseMessaging: FirebaseMessaging = mock()
    private val keychain: Keychain = mock()
    private val cacheStore: CacheStore = mock()

    private val lnurlService: LnurlService = mock()

    @Before
    fun setUp() {
        wheneverBlocking { coreService.shouldBlockLightning() }.thenReturn(false)
        sut = LightningRepo(
            bgDispatcher = testDispatcher,
            lightningService = lightningService,
            ldkNodeEventBus = ldkNodeEventBus,
            settingsStore = settingsStore,
            coreService = coreService,
            blocktankNotificationsService = blocktankNotificationsService,
            firebaseMessaging = firebaseMessaging,
            keychain = keychain,
            lnurlService = lnurlService,
            cacheStore = cacheStore,
        )
    }

    private suspend fun startNodeForTesting() {
        sut.setInitNodeLifecycleState()
        whenever(lightningService.node).thenReturn(mock())
        whenever(lightningService.setup(any(), anyOrNull(), anyOrNull())).thenReturn(Unit)
        whenever(lightningService.start(anyOrNull(), any())).thenReturn(Unit)
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))
        val result = sut.start()
        assertTrue(result.isSuccess)
    }

    @Test
    fun `start should transition through correct states`() = test {
        sut.setInitNodeLifecycleState()
        whenever(lightningService.node).thenReturn(mock())
        whenever(lightningService.setup(any(), anyOrNull(), anyOrNull())).thenReturn(Unit)
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
    fun `createInvoice should succeed when node is running`() = test {
        startNodeForTesting()
        val testInvoice = "testInvoice"
        whenever(
            lightningService.receive(
                sat = 100uL,
                description = "test",
                expirySecs = 3600u
            )
        ).thenReturn(testInvoice)

        val result = sut.createInvoice(amountSats = 100uL, description = "test", expirySeconds = 3600u)
        assertTrue(result.isSuccess)
        assertEquals(testInvoice, result.getOrNull())
    }

    @Test
    fun `payInvoice should fail when node is not running`() = test {
        val result = sut.payInvoice("bolt11", 1000uL)
        assertTrue(result.isFailure)
    }

    @Test
    fun `payInvoice should succeed when node is running`() = test {
        startNodeForTesting()
        val testPaymentId = "testPaymentId"
        whenever(lightningService.send("bolt11", 1000uL)).thenReturn(testPaymentId)

        val result = sut.payInvoice("bolt11", 1000uL)
        assertTrue(result.isSuccess)
        assertEquals(testPaymentId, result.getOrNull())
    }

    @Test
    fun `getPayments should fail when node is not running`() = test {
        val result = sut.getPayments()
        assertTrue(result.isFailure)
    }

    @Test
    fun `getPayments should succeed when node is running`() = test {
        startNodeForTesting()
        val testPayments = listOf(mock<PaymentDetails>())
        whenever(lightningService.payments).thenReturn(testPayments)

        val result = sut.getPayments()
        assertTrue(result.isSuccess)
        assertEquals(testPayments, result.getOrNull())
    }

    @Test
    fun `openChannel should fail when node is not running`() = test {
        val testPeer = LnPeer("nodeId", "host", "9735")
        val result = sut.openChannel(testPeer, 100000uL)
        assertTrue(result.isFailure)
    }

    @Test
    fun `openChannel should succeed when node is running`() = test {
        startNodeForTesting()
        val testPeer = LnPeer("nodeId", "host", "9735")
        val testChannelId = "testChannelId"
        val channelAmountSats = 100000uL
        whenever(lightningService.openChannel(peer = testPeer, channelAmountSats, null)).thenReturn(
            Result.success(
                testChannelId
            )
        )

        val result = sut.openChannel(testPeer, channelAmountSats, null)
        assertTrue(result.isSuccess)
        assertEquals(testChannelId, result.getOrNull())
    }

    @Test
    fun `closeChannel should fail when node is not running`() = test {
        val result = sut.closeChannel(createChannelDetails())
        assertTrue(result.isFailure)
    }

    @Test
    fun `closeChannel should succeed when node is running`() = test {
        startNodeForTesting()
        whenever(lightningService.closeChannel(any(), any(), any(), anyOrNull())).thenReturn(Unit)

        val result = sut.closeChannel(createChannelDetails())
        assertTrue(result.isSuccess)
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
    fun `canSend should return service value when node is running`() = test {
        startNodeForTesting()
        whenever(lightningService.canSend(any())).thenReturn(true)

        assertTrue(sut.canSend(1000uL))
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
    fun `connectToTrustedPeers should succeed when node is running`() = test {
        startNodeForTesting()
        whenever(lightningService.connectToTrustedPeers()).thenReturn(Unit)

        val result = sut.connectToTrustedPeers()
        assertTrue(result.isSuccess)
    }

    @Test
    fun `disconnectPeer should fail when node is not running`() = test {
        val testPeer = LnPeer("nodeId", "host", "9735")
        val result = sut.disconnectPeer(testPeer)
        assertTrue(result.isFailure)
    }

    @Test
    fun `disconnectPeer should succeed when node is running`() = test {
        startNodeForTesting()
        val testPeer = LnPeer("nodeId", "host", "9735")
        whenever(lightningService.disconnectPeer(any())).thenReturn(Unit)

        val result = sut.disconnectPeer(testPeer)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `sendOnChain should fail when node is not running`() = test {
        val result = sut.sendOnChain("address", 1000uL)
        assertTrue(result.isFailure)
    }

    @Test
    fun `sendOnChain should cache activity meta data`() = test {
        val mockSettingsData = SettingsData(
            defaultTransactionSpeed = TransactionSpeed.Fast,
            coinSelectAuto = false // Disable auto coin selection to simplify the test
        )
        whenever(settingsStore.data).thenReturn(flowOf(mockSettingsData))

        wheneverBlocking { cacheStore.addTransactionMetadata(any()) }.thenReturn(Unit)

        whenever(
            lightningService.send(
                address = any(),
                sats = any(),
                satsPerVByte = any(),
                utxosToSpend = anyOrNull()
            )
        ).thenReturn("testPaymentId")

        startNodeForTesting()

        // Create a spy to mock the getFeeRateForSpeed method
        val spySut = spy(sut)
        doReturn(Result.success(10uL)).whenever(spySut).getFeeRateForSpeed(any())

        val result = spySut.sendOnChain(
            address = "test_address",
            sats = 1000uL,
            speed = TransactionSpeed.Fast,
            utxosToSpend = null, // This was the missing parameter!
            isTransfer = true,
            channelId = "test_channel_id"
        )

        // Verify the result is successful
        assertTrue(result.isSuccess)
        assertEquals("testPaymentId", result.getOrNull())

        // Verify the cache call
        val captor = argumentCaptor<TransactionMetadata>()
        verifyBlocking(cacheStore) {
            addTransactionMetadata(captor.capture())
        }

        val capturedActivity = captor.firstValue
        assertEquals("testPaymentId", capturedActivity.txId)
        assertEquals("test_address", capturedActivity.address)
        assertEquals(true, capturedActivity.isTransfer)
        assertEquals("test_channel_id", capturedActivity.channelId)
        assertEquals("testPaymentId", capturedActivity.transferTxId)
        assertEquals(10u, capturedActivity.feeRate)
    }

    @Test
    fun `registerForNotifications should fail when node is not running`() = test {
        val result = sut.registerForNotifications()
        assertTrue(result.isFailure)
    }

    @Test
    fun `restartWithElectrumServer should setup with new server`() = test {
        startNodeForTesting()
        val customServer = mock<ElectrumServer>()
        whenever(lightningService.node).thenReturn(null)
        whenever(lightningService.stop()).thenReturn(Unit)

        val result = sut.restartWithElectrumServer(customServer)

        assertTrue(result.isSuccess)
        val inOrder = inOrder(lightningService)
        inOrder.verify(lightningService).stop()
        inOrder.verify(lightningService).setup(any(), eq(customServer), anyOrNull())
        inOrder.verify(lightningService).start(anyOrNull(), any())
        assertEquals(NodeLifecycleState.Running, sut.lightningState.value.nodeLifecycleState)
    }

    @Test
    fun `restartWithElectrumServer should handle stop failure`() = test {
        startNodeForTesting()
        val customServer = mock<ElectrumServer>()
        whenever(lightningService.stop()).thenThrow(RuntimeException("Stop failed"))

        val result = sut.restartWithElectrumServer(customServer)

        assertTrue(result.isFailure)
    }
}
