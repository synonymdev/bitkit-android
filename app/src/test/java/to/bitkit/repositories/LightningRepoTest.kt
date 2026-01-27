package to.bitkit.repositories

import app.cash.turbine.test
import com.google.firebase.messaging.FirebaseMessaging
import com.synonym.bitkitcore.FeeRates
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.ILspNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PeerDetails
import org.lightningdevkit.ldknode.SpendableUtxo
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.ext.createChannelDetails
import to.bitkit.ext.of
import to.bitkit.models.BalanceState
import to.bitkit.models.CoinSelectionPreference
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.OpenChannelResult
import to.bitkit.models.TransactionSpeed
import to.bitkit.services.BlocktankService
import to.bitkit.services.CoreService
import to.bitkit.services.LightningService
import to.bitkit.services.LnurlService
import to.bitkit.services.LspNotificationsService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LightningRepoTest : BaseUnitTest() {
    private lateinit var sut: LightningRepo

    private val lightningService = mock<LightningService>()
    private val settingsStore = mock<SettingsStore>()
    private val coreService = mock<CoreService>()
    private val lspNotificationsService = mock<LspNotificationsService>()
    private val firebaseMessaging = mock<FirebaseMessaging>()
    private val keychain = mock<Keychain>()
    private val cacheStore = mock<CacheStore>()
    private val preActivityMetadataRepo = mock<PreActivityMetadataRepo>()
    private val lnurlService = mock<LnurlService>()
    private val connectivityRepo = mock<ConnectivityRepo>()

    @Before
    fun setUp() = runBlocking {
        whenever(coreService.isGeoBlocked()).thenReturn(false)
        whenever(connectivityRepo.isOnline).thenReturn(MutableStateFlow(ConnectivityState.CONNECTED))
        sut = LightningRepo(
            bgDispatcher = testDispatcher,
            lightningService = lightningService,
            settingsStore = settingsStore,
            coreService = coreService,
            lspNotificationsService = lspNotificationsService,
            firebaseMessaging = firebaseMessaging,
            keychain = keychain,
            lnurlService = lnurlService,
            cacheStore = cacheStore,
            preActivityMetadataRepo = preActivityMetadataRepo,
            connectivityRepo = connectivityRepo,
        )
    }

    private suspend fun startNodeForTesting() {
        sut.setInitNodeLifecycleState()
        whenever(lightningService.node).thenReturn(mock())
        whenever(lightningService.setup(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(Unit)
        whenever(lightningService.start(anyOrNull(), any())).thenReturn(Unit)
        whenever(lightningService.sync()).thenReturn(Unit)
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))
        val blocktank = mock<BlocktankService>()
        whenever(coreService.blocktank).thenReturn(blocktank)
        whenever(blocktank.info(any())).thenReturn(null)
        val result = sut.start()
        assertTrue(result.isSuccess)
        // Simulate successful sync to set isSyncHealthy = true
        sut.sync()
    }

    @Test
    fun `start should transition through correct states`() = test {
        sut.setInitNodeLifecycleState()
        whenever(lightningService.node).thenReturn(mock())
        whenever(lightningService.setup(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(Unit)
        whenever(lightningService.start(anyOrNull(), any())).thenReturn(Unit)
        val blocktank = mock<BlocktankService>()
        whenever(coreService.blocktank).thenReturn(blocktank)
        whenever(blocktank.info(any())).thenReturn(null)

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
        val testPeer = PeerDetails.of("nodeId", "host", "9735")
        val result = sut.openChannel(testPeer, 100000uL)
        assertTrue(result.isFailure)
    }

    @Test
    fun `openChannel should succeed when node is running`() = test {
        startNodeForTesting()
        val peer = PeerDetails.of("nodeId", "host", "9735")
        val userChannelId = "testChannelId"
        val channelAmountSats = 100_000uL
        whenever(lightningService.openChannel(peer, channelAmountSats, null, null))
            .thenReturn(Result.success(OpenChannelResult(userChannelId, peer, channelAmountSats)))

        val result = sut.openChannel(peer, channelAmountSats, null)
        assertTrue(result.isSuccess)
        assertEquals(userChannelId, result.getOrNull()?.userChannelId)
    }

    @Test
    fun `closeChannel should fail when node is not running`() = test {
        val result = sut.closeChannel(createChannelDetails())
        assertTrue(result.isFailure)
    }

    @Test
    fun `closeChannel should succeed when node is running`() = test {
        startNodeForTesting()
        whenever(lightningService.closeChannel(any(), any(), anyOrNull())).thenReturn(Unit)

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
    fun `canReceive should return false when node is not running`() = test {
        assertFalse(sut.canReceive())
    }

    @Test
    fun `canReceive should return false when node is running but cannot receive`() = test {
        startNodeForTesting()
        whenever(lightningService.canReceive()).thenReturn(false)

        assertFalse(sut.canReceive())
    }

    @Test
    fun `canReceive should return true when node can receive`() = test {
        startNodeForTesting()
        whenever(lightningService.canReceive()).thenReturn(true)

        assertTrue(sut.canReceive())
    }

    @Test
    fun `syncState should update state with current values`() = test {
        startNodeForTesting()
        val testNodeId = "test_node_id"
        val testStatus = mock<NodeStatus>()
        val testPeers = listOf(mock<PeerDetails>())
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
    fun `canSend should use cached outbound when node is not running`() = test {
        val cacheData = AppCacheData(
            balance = BalanceState(
                maxSendLightningSats = 2000uL
            )
        )
        whenever(cacheStore.data).thenReturn(flowOf(cacheData))

        assert(sut.canSend(1000uL, fallbackToCachedBalance = true))
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
        val testPeer = PeerDetails.of("nodeId", "host", "9735")
        val result = sut.disconnectPeer(testPeer)
        assertTrue(result.isFailure)
    }

    @Test
    fun `disconnectPeer should succeed when node is running`() = test {
        startNodeForTesting()
        val testPeer = PeerDetails.of("nodeId", "host", "9735")
        whenever(lightningService.disconnectPeer(any())).thenReturn(Result.success(Unit))

        val result = sut.disconnectPeer(testPeer)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `sendOnChain should fail when node is not running`() = test {
        val result = sut.sendOnChain("address", 1000uL)
        assertTrue(result.isFailure)
    }

    @Test
    fun `sendOnChain should fail when sync is unhealthy`() = test {
        // Start node but make sync fail (isSyncHealthy = false)
        // Mock connectivity as disconnected to prevent retry loop from running indefinitely
        whenever(connectivityRepo.isOnline).thenReturn(MutableStateFlow(ConnectivityState.DISCONNECTED))
        sut.setInitNodeLifecycleState()
        whenever(lightningService.node).thenReturn(mock())
        whenever(lightningService.setup(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(Unit)
        whenever(lightningService.start(anyOrNull(), any())).thenReturn(Unit)
        whenever(lightningService.sync()).thenThrow(RuntimeException("Sync failed"))
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))
        val blocktank = mock<BlocktankService>()
        whenever(coreService.blocktank).thenReturn(blocktank)
        whenever(blocktank.info(any())).thenReturn(null)
        sut.start()

        // Sync failed during start(), so isSyncHealthy should be false

        val result = sut.sendOnChain("address", 1000uL)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SyncUnhealthyError)
    }

    @Test
    fun `sendOnChain should cache activity meta data`() = test {
        val mockSettingsData = SettingsData(
            defaultTransactionSpeed = TransactionSpeed.Fast,
            coinSelectAuto = false // Disable auto coin selection to simplify the test
        )
        whenever(settingsStore.data).thenReturn(flowOf(mockSettingsData))

        whenever(preActivityMetadataRepo.addPreActivityMetadata(any())).thenReturn(Result.success(Unit))

        whenever(
            lightningService.send(
                address = any(),
                sats = any(),
                satsPerVByte = any(),
                utxosToSpend = anyOrNull(),
                isMaxAmount = any()
            )
        ).thenReturn("testPaymentId")

        startNodeForTesting()

        // Create a spy to mock the getFeeRateForSpeed method
        val spySut = spy(sut)
        doReturn(Result.success(10uL)).whenever(spySut).getFeeRateForSpeed(any(), anyOrNull())

        val result = spySut.sendOnChain(
            address = "test_address",
            sats = 1000uL,
            speed = TransactionSpeed.Fast,
            utxosToSpend = null,
            feeRates = null,
            isTransfer = true,
            channelId = "test_channel_id"
        )

        // Verify the result is successful
        assertTrue(result.isSuccess)
        assertEquals("testPaymentId", result.getOrNull())

        // Verify pre-activity metadata was saved
        verifyBlocking(preActivityMetadataRepo) {
            addPreActivityMetadata(any())
        }
    }

    @Test
    fun `registerForNotifications should fail when node is not running`() = test {
        val result = sut.registerForNotifications()
        assertTrue(result.isFailure)
    }

    @Test
    fun `restartWithElectrumServer should setup with new server`() = test {
        startNodeForTesting()
        val customServerUrl = "ssl://test.example.com:50002"
        whenever(lightningService.node).thenReturn(null)
        whenever(lightningService.stop()).thenReturn(Unit)

        val result = sut.restartWithElectrumServer(customServerUrl)

        assertTrue(result.isSuccess)
        val inOrder = inOrder(lightningService)
        inOrder.verify(lightningService).stop()
        inOrder.verify(lightningService).setup(any(), eq(customServerUrl), anyOrNull(), anyOrNull(), anyOrNull())
        inOrder.verify(lightningService).start(anyOrNull(), any())
        assertEquals(NodeLifecycleState.Running, sut.lightningState.value.nodeLifecycleState)
    }

    @Test
    fun `restartWithElectrumServer should handle stop failure`() = test {
        startNodeForTesting()
        val customServerUrl = "ssl://test.example.com:50002"
        whenever(lightningService.stop()).thenThrow(RuntimeException("Stop failed"))

        val result = sut.restartWithElectrumServer(customServerUrl)

        assertTrue(result.isFailure)
    }

    @Test
    fun `getFeeRateForSpeed should use provided feeRates`() = test {
        val mockFeeRates = mock<FeeRates>()
        whenever(mockFeeRates.mid).thenReturn(20u)

        val result = sut.getFeeRateForSpeed(TransactionSpeed.Medium, mockFeeRates)

        assertTrue(result.isSuccess)
        assertEquals(20uL, result.getOrNull())
    }

    @Test
    fun `getFeeRateForSpeed should fetch from blocktank when feeRates is null`() = test {
        val mockFeeRates = mock<FeeRates>()
        whenever(mockFeeRates.fast).thenReturn(30u)
        val blocktank = mock<BlocktankService>()
        whenever(blocktank.getFees()).thenReturn(Result.success(mockFeeRates))
        whenever(coreService.blocktank).thenReturn(blocktank)

        val result = sut.getFeeRateForSpeed(TransactionSpeed.Fast, null)

        assertTrue(result.isSuccess)
        assertEquals(30uL, result.getOrNull())
    }

    @Test
    fun `determineUtxosToSpend should return null when coinSelectAuto is false`() = test {
        val mockSettingsData = SettingsData(coinSelectAuto = false)
        whenever(settingsStore.data).thenReturn(flowOf(mockSettingsData))

        val result = sut.determineUtxosToSpend(1000uL, 10u)

        assertNull(result)
    }

    @Test
    fun `determineUtxosToSpend should return all UTXOs when preference is Consolidate`() = test {
        val mockSettingsData = SettingsData(
            coinSelectAuto = true,
            coinSelectPreference = CoinSelectionPreference.Consolidate
        )
        whenever(settingsStore.data).thenReturn(flowOf(mockSettingsData))

        val mockUtxos = listOf(
            mock<SpendableUtxo>(),
            mock<SpendableUtxo>(),
            mock<SpendableUtxo>()
        )
        whenever(lightningService.listSpendableOutputs()).thenReturn(Result.success(mockUtxos))

        val result = sut.determineUtxosToSpend(1000uL, 10u)

        assertNotNull(result)
        assertEquals(3, result.size)
        assertEquals(mockUtxos, result)
    }

    @Test
    fun `estimateRoutingFees should fail when node is not running`() = test {
        val result = sut.estimateRoutingFees("lnbc1u1p0abcde")
        assertTrue(result.isFailure)
    }

    @Test
    fun `estimateRoutingFees should succeed when node is running`() = test {
        startNodeForTesting()
        val testBolt11 = "lnbc1u1p0abcde"
        val expectedFeesSats = 50uL

        whenever(lightningService.estimateRoutingFees(testBolt11))
            .thenReturn(Result.success(expectedFeesSats))

        val result = sut.estimateRoutingFees(testBolt11)

        assertTrue(result.isSuccess)
        assertEquals(expectedFeesSats, result.getOrNull())
        verify(lightningService).estimateRoutingFees(testBolt11)
    }

    @Test
    fun `estimateRoutingFees should handle service failure`() = test {
        startNodeForTesting()
        val testBolt11 = "lnbc1u1p0abcde"
        val serviceError = RuntimeException("Service error")

        whenever(lightningService.estimateRoutingFees(testBolt11))
            .thenReturn(Result.failure(serviceError))

        val result = sut.estimateRoutingFees(testBolt11)

        assertTrue(result.isFailure)
        assertEquals(serviceError, result.exceptionOrNull())
    }

    @Test
    fun `estimateRoutingFeesForAmount should fail when node is not running`() = test {
        val result = sut.estimateRoutingFeesForAmount("lnbc1u1p0abcde", 1000uL)
        assertTrue(result.isFailure)
    }

    @Test
    fun `estimateRoutingFeesForAmount should succeed when node is running`() = test {
        startNodeForTesting()
        val testBolt11 = "lnbc1u1p0abcde"
        val testAmount = 1000uL
        val expectedFeesSats = 25uL

        whenever(lightningService.estimateRoutingFeesForAmount(testBolt11, testAmount))
            .thenReturn(Result.success(expectedFeesSats))

        val result = sut.estimateRoutingFeesForAmount(testBolt11, testAmount)

        assertTrue(result.isSuccess)
        assertEquals(expectedFeesSats, result.getOrNull())
        verify(lightningService).estimateRoutingFeesForAmount(testBolt11, testAmount)
    }

    @Test
    fun `estimateRoutingFeesForAmount should handle service failure`() = test {
        startNodeForTesting()
        val testBolt11 = "lnbc1u1p0abcde"
        val testAmount = 1000uL
        val serviceError = RuntimeException("Service error")

        whenever(lightningService.estimateRoutingFeesForAmount(testBolt11, testAmount))
            .thenReturn(Result.failure(serviceError))

        val result = sut.estimateRoutingFeesForAmount(testBolt11, testAmount)

        assertTrue(result.isFailure)
        assertEquals(serviceError, result.exceptionOrNull())
    }

    @Test
    fun `start should load trusted peers from blocktank info`() = test {
        sut.setInitNodeLifecycleState()
        whenever(lightningService.node).thenReturn(null)
        whenever(lightningService.setup(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(Unit)
        whenever(lightningService.start(anyOrNull(), any())).thenReturn(Unit)
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))

        val blocktank = mock<BlocktankService>()
        whenever(coreService.blocktank).thenReturn(blocktank)

        val mockNodes = listOf(
            ILspNode(
                alias = "LSP1",
                pubkey = "node1pubkey",
                connectionStrings = listOf("node1pubkey@node1.example.com:9735"),
                readonly = null,
            ),
            ILspNode(
                alias = "LSP2",
                pubkey = "node2pubkey",
                connectionStrings = listOf("node2pubkey@node2.example.com:9735"),
                readonly = null,
            ),
        )
        val mockInfo = mock<IBtInfo> { on { nodes } doReturn mockNodes }
        whenever(blocktank.info(refresh = false)).thenReturn(mockInfo)

        val result = sut.start()

        assertTrue(result.isSuccess)
        verify(lightningService).setup(
            any(),
            anyOrNull(),
            anyOrNull(),
            argThat { peers ->
                peers?.size == 2 &&
                    peers.any { it.nodeId == "node1pubkey" && it.address == "node1.example.com:9735" } &&
                    peers.any { it.nodeId == "node2pubkey" && it.address == "node2.example.com:9735" }
            },
            anyOrNull(),
        )
    }

    @Test
    fun `start should pass null trusted peers when blocktank returns null`() = test {
        sut.setInitNodeLifecycleState()
        whenever(lightningService.node).thenReturn(null)
        whenever(lightningService.setup(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(Unit)
        whenever(lightningService.start(anyOrNull(), any())).thenReturn(Unit)
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))

        val blocktank = mock<BlocktankService>()
        whenever(coreService.blocktank).thenReturn(blocktank)
        whenever(blocktank.info(refresh = false)).thenReturn(null)
        whenever(blocktank.info(refresh = true)).thenReturn(null)

        val result = sut.start()

        assertTrue(result.isSuccess)
        verify(lightningService).setup(any(), anyOrNull(), anyOrNull(), isNull(), anyOrNull())
    }

    @Test
    fun `start should not retry when node lifecycle state is Running`() = test {
        sut.setInitNodeLifecycleState()
        whenever(lightningService.node).thenReturn(null)
        whenever(lightningService.setup(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(Unit)
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))
        val blocktank = mock<BlocktankService>()
        whenever(coreService.blocktank).thenReturn(blocktank)
        whenever(blocktank.info(any())).thenReturn(null)

        // lightningService.start() succeeds (state becomes Running at line 241)
        whenever(lightningService.start(anyOrNull(), any())).thenReturn(Unit)
        // lightningService.nodeId throws during syncState() (called at line 244, AFTER state = Running)
        whenever(lightningService.nodeId).thenThrow(RuntimeException("error during syncState"))

        val result = sut.start()

        // Defensive check: state is Running, so don't retry, return success
        assertTrue(result.isSuccess)
        assertEquals(NodeLifecycleState.Running, sut.lightningState.value.nodeLifecycleState)
        // Verify start was only called once (no retry)
        verify(lightningService, times(1)).start(anyOrNull(), any())
    }
}
