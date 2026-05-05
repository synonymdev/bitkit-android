package to.bitkit.repositories

import app.cash.turbine.test
import com.google.firebase.messaging.FirebaseMessaging
import com.synonym.bitkitcore.AddressType
import com.synonym.bitkitcore.FeeRates
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.ILspNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.AddressTypeBalance
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
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
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.backup.VssBackupClientLdk
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Defaults
import to.bitkit.ext.createChannelDetails
import to.bitkit.ext.of
import to.bitkit.models.CoinSelectionPreference
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.OpenChannelResult
import to.bitkit.models.TransactionSpeed
import to.bitkit.services.BlocktankService
import to.bitkit.services.CoreService
import to.bitkit.services.LightningService
import to.bitkit.services.LnurlService
import to.bitkit.services.LspNotificationsService
import to.bitkit.services.NodeEventHandler
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.UrlValidator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Suppress("LargeClass")
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
    private val vssBackupClientLdk = mock<VssBackupClientLdk>()
    private val urlValidator = UrlValidator { Result.success(Unit) }
    private val probePaymentA = "probe-payment-a"
    private val probePaymentB = "probe-payment-b"
    private val probeHashA = "probe-hash-a"
    private val probeHashB = "probe-hash-b"
    private val probeNodeId = "02abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdef12"

    @Before
    fun setUp() = runBlocking {
        whenever(lightningService.setup(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(Unit)
        whenever(lightningService.start(anyOrNull(), any())).thenReturn(Unit)
        whenever(coreService.isGeoBlocked()).thenReturn(false)
        whenever(connectivityRepo.isOnline).thenReturn(MutableStateFlow(ConnectivityState.CONNECTED))
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))
        whenever(lightningService.aresRequiredPeersInNetworkGraph()).thenReturn(true)
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
            vssBackupClientLdk = vssBackupClientLdk,
            urlValidator = urlValidator,
        )
    }

    private suspend fun startNodeForTesting() {
        sut.setInitNodeLifecycleState()
        whenever(lightningService.node).thenReturn(mock())
        whenever(lightningService.sync()).thenReturn(Unit)
        val blocktank = mock<BlocktankService>()
        whenever(coreService.blocktank).thenReturn(blocktank)
        whenever(blocktank.info(any())).thenReturn(null)
        val result = sut.start()
        assertTrue(result.isSuccess)
        // Simulate successful sync to set isSyncHealthy = true
        sut.sync()
    }

    private suspend fun startNodeAndCaptureEvents(): NodeEventHandler {
        var capturedHandler: NodeEventHandler? = null
        whenever { lightningService.start(anyOrNull(), any()) }.thenAnswer {
            @Suppress("UNCHECKED_CAST")
            capturedHandler = it.arguments[1] as NodeEventHandler
            Unit
        }

        startNodeForTesting()
        return requireNotNull(capturedHandler)
    }

    @Test
    fun `start should transition through correct states`() = test {
        sut.setInitNodeLifecycleState()
        whenever(lightningService.node).thenReturn(mock())
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
                expirySecs = Defaults.bolt11InvoiceExpirySeconds,
            )
        ).thenReturn(testInvoice)

        val result = sut.createInvoice(
            amountSats = 100uL,
            description = "test",
            expirySeconds = Defaults.bolt11InvoiceExpirySeconds,
        )
        assertTrue(result.isSuccess)
        assertEquals(testInvoice, result.getOrNull())
    }

    @Test
    fun `payInvoice should fail when node is not running`() = test {
        val result = sut.payInvoice("bolt11", 1000uL)
        assertTrue(result.isFailure)
    }

    @Test
    fun `payInvoice should succeed when node is running and channels are usable`() = test {
        startNodeForTesting()
        val usableChannel = createChannelDetails().copy(isUsable = true)
        whenever(lightningService.channels).thenReturn(listOf(usableChannel))
        val testPaymentId = "testPaymentId"
        whenever(lightningService.send("bolt11", 1000uL)).thenReturn(testPaymentId)

        val result = sut.payInvoice("bolt11", 1000uL)
        assertTrue(result.isSuccess)
        assertEquals(testPaymentId, result.getOrNull())
    }

    @Test
    fun `payInvoice should proceed after timeout when channels are not usable`() = test {
        startNodeForTesting()
        val testPaymentId = "testPaymentId"
        whenever(lightningService.send("bolt11", 1000uL)).thenReturn(testPaymentId)

        // Channels are ready but not usable (peer disconnected)
        val readyButNotUsable = createChannelDetails().copy(isChannelReady = true, isUsable = false)
        whenever(lightningService.channels).thenReturn(listOf(readyButNotUsable))

        // payInvoice should wait, timeout, then still attempt to send
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
    fun `canSend should return false when node is stopped`() = test {
        assertFalse(sut.canSend(1000uL, fallbackToCachedBalance = true))
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
        whenever(lightningService.sync()).thenThrow(RuntimeException("Sync failed"))
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
        whenever(coreService.activity).thenReturn(mock())

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
    fun `restartWithRgsServer should setup with new rgs server`() = test {
        startNodeForTesting()
        val customRgsUrl = "https://rgs.example.com/snapshot"
        whenever(lightningService.node).thenReturn(null)
        whenever(lightningService.stop()).thenReturn(Unit)

        val result = sut.restartWithRgsServer(customRgsUrl)

        assertTrue(result.isSuccess)
        val inOrder = inOrder(lightningService)
        inOrder.verify(lightningService).stop()
        inOrder.verify(lightningService).setup(any(), isNull(), eq(customRgsUrl), anyOrNull(), anyOrNull())
        inOrder.verify(lightningService).start(anyOrNull(), any())
        assertEquals(NodeLifecycleState.Running, sut.lightningState.value.nodeLifecycleState)
    }

    @Test
    fun `restartWithRgsServer should handle stop failure`() = test {
        startNodeForTesting()
        whenever(lightningService.stop()).thenThrow(RuntimeException("Stop failed"))

        val result = sut.restartWithRgsServer("https://rgs.example.com/snapshot")

        assertTrue(result.isFailure)
    }

    @Test
    fun `restartWithRgsServer should handle start failure and recover`() = test {
        startNodeForTesting()
        whenever(lightningService.node).thenReturn(null)
        whenever(lightningService.stop()).thenReturn(Unit)
        whenever(lightningService.setup(any(), isNull(), eq("https://bad.rgs/snapshot"), anyOrNull(), anyOrNull()))
            .thenThrow(RuntimeException("Failed to start node"))

        val result = sut.restartWithRgsServer("https://bad.rgs/snapshot")

        assertTrue(result.isFailure)
    }

    @Test
    fun `restartWithRgsServer should fail when url is unreachable`() = test {
        val failingValidator = UrlValidator { Result.failure(Exception("DNS resolution failed")) }
        val sutWithFailingValidator = LightningRepo(
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
            vssBackupClientLdk = vssBackupClientLdk,
            urlValidator = failingValidator,
        )
        sutWithFailingValidator.setInitNodeLifecycleState()
        whenever(lightningService.node).thenReturn(mock())
        whenever(lightningService.sync()).thenReturn(Unit)
        val blocktank = mock<BlocktankService>()
        whenever(coreService.blocktank).thenReturn(blocktank)
        whenever(blocktank.info(any())).thenReturn(null)
        sutWithFailingValidator.start()

        val result = sutWithFailingValidator.restartWithRgsServer("https://rapidsync.lightningdevkit/snapshot")

        assertTrue(result.isFailure)
        assertEquals("DNS resolution failed", result.exceptionOrNull()?.message)
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

        val blocktank = mock<BlocktankService>()
        whenever(coreService.blocktank).thenReturn(blocktank)
        whenever(blocktank.info(refresh = false)).thenReturn(null)
        whenever(blocktank.info(refresh = true)).thenReturn(null)

        val result = sut.start()

        assertTrue(result.isSuccess)
        verify(lightningService).setup(any(), anyOrNull(), anyOrNull(), isNull(), anyOrNull())
    }

    @Test
    fun `getBalanceForAddressType should succeed when node is running`() = test {
        startNodeForTesting()
        whenever(lightningService.getBalanceForAddressType(AddressType.P2WPKH))
            .thenReturn(AddressTypeBalance(totalSats = 50_000uL, spendableSats = 50_000uL))

        val result = sut.getBalanceForAddressType(AddressType.P2WPKH)

        assertTrue(result.isSuccess)
        assertEquals(50_000uL, result.getOrNull())
    }

    @Test
    fun `getBalanceForAddressType should fail when node is not running`() = test {
        val result = sut.getBalanceForAddressType(AddressType.P2WPKH)

        assertTrue(result.isFailure)
    }

    @Test
    fun `getChannelFundableBalance should return aggregate spendable when per-type fails`() = test {
        startNodeForTesting()
        whenever(
            settingsStore.data
        ).thenReturn(
            flowOf(SettingsData(selectedAddressType = "nativeSegwit", addressTypesToMonitor = listOf("nativeSegwit")))
        )
        whenever(lightningService.getBalanceForAddressType(any()))
            .thenThrow(UnsupportedOperationException("per-type not supported"))
        whenever(lightningService.balances).thenReturn(
            BalanceDetails(
                totalOnchainBalanceSats = 100_000uL,
                spendableOnchainBalanceSats = 80_000uL,
                totalAnchorChannelsReserveSats = 0uL,
                totalLightningBalanceSats = 0uL,
                lightningBalances = emptyList(),
                pendingBalancesFromChannelClosures = emptyList(),
            ),
        )

        val result = sut.getChannelFundableBalance()

        assertEquals(80_000uL, result)
    }

    @Test
    fun `updateAddressType should fail when already in progress`() = test {
        startNodeForTesting()
        val settingsFlow = MutableSharedFlow<SettingsData>(replay = 1)
        whenever(settingsStore.data).thenReturn(settingsFlow)
        whenever { settingsStore.update(any()) }.thenReturn(Unit)

        val scope = CoroutineScope(testDispatcher)
        val job1 = scope.async {
            sut.updateAddressType("taproot", listOf("taproot", "nativeSegwit"))
        }
        testScheduler.advanceUntilIdle()
        val job2 = scope.async { sut.updateAddressType("legacy", listOf("legacy")) }
        val result2 = job2.await()
        settingsFlow.emit(
            SettingsData(
                selectedAddressType = "nativeSegwit",
                addressTypesToMonitor = listOf("nativeSegwit"),
            ),
        )
        val result1 = job1.await()

        assertTrue(result2.isFailure)
        assertTrue(result2.exceptionOrNull()?.message?.contains("already in progress") == true)
        assertTrue(result1.isSuccess)
    }

    @Test
    fun `setMonitoring should fail when disabling currently selected type`() = test {
        startNodeForTesting()
        whenever(
            settingsStore.data
        ).thenReturn(
            flowOf(
                SettingsData(
                    selectedAddressType = "taproot",
                    addressTypesToMonitor = listOf("nativeSegwit", "taproot")
                )
            )
        )

        val result = sut.setMonitoring(AddressType.P2TR, enabled = false)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("currently selected") == true)
        verify(lightningService, times(0)).removeAddressTypeFromMonitor(any())
    }

    @Test
    fun `setMonitoring should fail when disabling last required native witness`() = test {
        startNodeForTesting()
        whenever(
            settingsStore.data
        ).thenReturn(
            flowOf(
                SettingsData(
                    selectedAddressType = "legacy",
                    addressTypesToMonitor = listOf("taproot")
                )
            )
        )
        whenever(lightningService.getBalanceForAddressType(AddressType.P2TR))
            .thenReturn(AddressTypeBalance(totalSats = 0uL, spendableSats = 0uL))

        val result = sut.setMonitoring(AddressType.P2TR, enabled = false)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Native SegWit or Taproot") == true)
        verify(lightningService, times(0)).removeAddressTypeFromMonitor(any())
    }

    @Test
    fun `setMonitoring should fail when balance verification fails`() = test {
        startNodeForTesting()
        whenever(
            settingsStore.data
        ).thenReturn(
            flowOf(
                SettingsData(
                    selectedAddressType = "nativeSegwit",
                    addressTypesToMonitor = listOf("nativeSegwit", "taproot")
                )
            )
        )
        whenever(lightningService.getBalanceForAddressType(AddressType.P2TR))
            .thenThrow(RuntimeException("balance check failed"))

        val result = sut.setMonitoring(AddressType.P2TR, enabled = false)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("verify") == true)
        verify(lightningService, times(0)).removeAddressTypeFromMonitor(any())
    }

    @Test
    fun `setMonitoring should fail when disabling with balance greater than zero`() = test {
        startNodeForTesting()
        whenever(
            settingsStore.data
        ).thenReturn(
            flowOf(
                SettingsData(
                    selectedAddressType = "nativeSegwit",
                    addressTypesToMonitor = listOf("nativeSegwit", "taproot")
                )
            )
        )
        whenever(lightningService.getBalanceForAddressType(AddressType.P2TR))
            .thenReturn(AddressTypeBalance(totalSats = 1_000uL, spendableSats = 1_000uL))

        val result = sut.setMonitoring(AddressType.P2TR, enabled = false)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("has balance") == true)
        verify(lightningService, times(0)).removeAddressTypeFromMonitor(any())
    }

    @Test
    fun `setMonitoring should succeed when enabling a type`() = test {
        startNodeForTesting()
        whenever(
            settingsStore.data
        ).thenReturn(
            flowOf(
                SettingsData(
                    selectedAddressType = "nativeSegwit",
                    addressTypesToMonitor = listOf("nativeSegwit")
                )
            )
        )
        whenever { settingsStore.update(any()) }.thenReturn(Unit)

        val result = sut.setMonitoring(AddressType.P2TR, enabled = true)

        assertTrue(result.isSuccess)
        verify(lightningService).addAddressTypeToMonitor(AddressType.P2TR)
    }

    @Test
    fun `setMonitoring should succeed when disabling when allowed`() = test {
        startNodeForTesting()
        whenever(
            settingsStore.data
        ).thenReturn(
            flowOf(
                SettingsData(
                    selectedAddressType = "nativeSegwit",
                    addressTypesToMonitor = listOf("nativeSegwit", "taproot")
                )
            )
        )
        whenever(lightningService.getBalanceForAddressType(AddressType.P2TR))
            .thenReturn(AddressTypeBalance(totalSats = 0uL, spendableSats = 0uL))
        whenever { settingsStore.update(any()) }.thenReturn(Unit)

        val result = sut.setMonitoring(AddressType.P2TR, enabled = false)

        assertTrue(result.isSuccess)
        verify(lightningService).removeAddressTypeFromMonitor(AddressType.P2TR)
    }

    @Test
    fun `updateAddressType should succeed`() = test {
        startNodeForTesting()
        whenever(
            settingsStore.data
        ).thenReturn(
            flowOf(SettingsData(selectedAddressType = "nativeSegwit", addressTypesToMonitor = listOf("nativeSegwit")))
        )
        whenever { settingsStore.update(any()) }.thenReturn(Unit)

        val result = sut.updateAddressType("taproot", listOf("taproot", "nativeSegwit"))

        assertTrue(result.isSuccess)
        verify(lightningService).setPrimaryAddressType(AddressType.P2TR)
    }

    @Test
    fun `updateAddressType should fail when setPrimaryAddressType fails`() = test {
        startNodeForTesting()
        whenever(
            settingsStore.data
        ).thenReturn(
            flowOf(SettingsData(selectedAddressType = "nativeSegwit", addressTypesToMonitor = listOf("nativeSegwit")))
        )
        whenever { settingsStore.update(any()) }.thenReturn(Unit)
        whenever(lightningService.setPrimaryAddressType(any()))
            .thenThrow(RuntimeException("setPrimaryAddressType failed"))

        val result = sut.updateAddressType("taproot", listOf("taproot", "nativeSegwit"))

        assertTrue(result.isFailure)
        // Verify rollback happened (update called twice: once for new settings, once for rollback)
        verifyBlocking(settingsStore, times(2)) { update(any()) }
    }

    @Test
    fun `getChannelFundableBalance should sum spendable across monitored types excluding legacy`() = test {
        startNodeForTesting()
        whenever(settingsStore.data).thenReturn(
            flowOf(
                SettingsData(
                    selectedAddressType = "nativeSegwit",
                    addressTypesToMonitor = listOf("nativeSegwit", "taproot", "legacy")
                )
            )
        )
        whenever(lightningService.getBalanceForAddressType(AddressType.P2WPKH))
            .thenReturn(AddressTypeBalance(totalSats = 50_000uL, spendableSats = 40_000uL))
        whenever(lightningService.getBalanceForAddressType(AddressType.P2TR))
            .thenReturn(AddressTypeBalance(totalSats = 30_000uL, spendableSats = 20_000uL))
        whenever(lightningService.getBalanceForAddressType(AddressType.P2PKH))
            .thenReturn(AddressTypeBalance(totalSats = 10_000uL, spendableSats = 10_000uL))

        val result = sut.getChannelFundableBalance()

        // 40_000 (P2WPKH) + 20_000 (P2TR) = 60_000; legacy 10_000 excluded
        assertEquals(60_000uL, result)
    }

    @Test
    fun `pruneEmptyAddressTypesAfterRestore should remove empty non-selected types`() = test {
        startNodeForTesting()
        whenever(settingsStore.data).thenReturn(
            flowOf(
                SettingsData(
                    selectedAddressType = "nativeSegwit",
                    addressTypesToMonitor = listOf("nativeSegwit", "taproot", "legacy")
                )
            )
        )
        whenever(lightningService.getBalanceForAddressType(AddressType.P2TR))
            .thenReturn(AddressTypeBalance(totalSats = 0uL, spendableSats = 0uL))
        whenever(lightningService.getBalanceForAddressType(AddressType.P2PKH))
            .thenReturn(AddressTypeBalance(totalSats = 0uL, spendableSats = 0uL))
        whenever { settingsStore.update(any()) }.thenReturn(Unit)

        val result = sut.pruneEmptyAddressTypesAfterRestore()

        assertTrue(result.isSuccess)
        verify(lightningService).removeAddressTypeFromMonitor(AddressType.P2TR)
        verify(lightningService).removeAddressTypeFromMonitor(AddressType.P2PKH)
        // Selected type (nativeSegwit/P2WPKH) must not be removed
        verify(lightningService, times(0)).removeAddressTypeFromMonitor(AddressType.P2WPKH)
    }

    @Test
    fun `pruneEmptyAddressTypesAfterRestore should keep types with balance`() = test {
        startNodeForTesting()
        whenever(settingsStore.data).thenReturn(
            flowOf(
                SettingsData(
                    selectedAddressType = "nativeSegwit",
                    addressTypesToMonitor = listOf("nativeSegwit", "taproot")
                )
            )
        )
        whenever(lightningService.getBalanceForAddressType(AddressType.P2TR))
            .thenReturn(AddressTypeBalance(totalSats = 5_000uL, spendableSats = 5_000uL))

        val result = sut.pruneEmptyAddressTypesAfterRestore()

        assertTrue(result.isSuccess)
        verify(lightningService, times(0)).removeAddressTypeFromMonitor(any())
    }

    @Test
    fun `pruneEmptyAddressTypesAfterRestore should not remove last native witness type`() = test {
        startNodeForTesting()
        whenever(settingsStore.data).thenReturn(
            flowOf(
                SettingsData(
                    selectedAddressType = "legacy",
                    addressTypesToMonitor = listOf("legacy", "nativeSegwit")
                )
            )
        )
        whenever(lightningService.getBalanceForAddressType(AddressType.P2WPKH))
            .thenReturn(AddressTypeBalance(totalSats = 0uL, spendableSats = 0uL))

        val result = sut.pruneEmptyAddressTypesAfterRestore()

        assertTrue(result.isSuccess)
        verify(lightningService, times(0)).removeAddressTypeFromMonitor(any())
    }

    @Test
    fun `pruneEmptyAddressTypesAfterRestore should skip when address type change in progress`() = test {
        startNodeForTesting()
        whenever(settingsStore.data).thenReturn(
            flowOf(
                SettingsData(
                    selectedAddressType = "nativeSegwit",
                    addressTypesToMonitor = listOf("nativeSegwit", "taproot")
                )
            )
        )
        whenever { settingsStore.update(any()) }.thenReturn(Unit)
        // Start an address type change to set isChangingAddressType
        val settingsFlow = MutableSharedFlow<SettingsData>()
        whenever(settingsStore.data).thenReturn(settingsFlow)
        val scope = CoroutineScope(testDispatcher)
        scope.async { sut.updateAddressType("taproot", listOf("taproot")) }
        testScheduler.advanceUntilIdle()

        val result = sut.pruneEmptyAddressTypesAfterRestore()

        assertTrue(result.isSuccess)
        verify(lightningService, times(0)).removeAddressTypeFromMonitor(any())
    }

    @Test
    fun `setMonitoring should rollback on service failure`() = test {
        startNodeForTesting()
        whenever(settingsStore.data).thenReturn(
            flowOf(
                SettingsData(
                    selectedAddressType = "nativeSegwit",
                    addressTypesToMonitor = listOf("nativeSegwit")
                )
            )
        )
        whenever { settingsStore.update(any()) }.thenReturn(Unit)
        whenever(lightningService.addAddressTypeToMonitor(any()))
            .thenThrow(RuntimeException("service error"))

        val result = sut.setMonitoring(AddressType.P2TR, enabled = true)

        assertTrue(result.isFailure)
        // Verify rollback happened (update called twice: once for new, once for rollback)
        verifyBlocking(settingsStore, times(2)) { update(any()) }
    }

    @Test
    fun `waitForProbeOutcome returns success when ProbeSuccessful arrives after subscription`() = test {
        val onEvent = startNodeAndCaptureEvents()

        val result = async { sut.waitForProbeOutcome(setOf(probePaymentA)) }
        onEvent(Event.ProbeSuccessful(paymentId = probePaymentA, paymentHash = probeHashA))

        val outcome = result.await().getOrThrow()
        assertIs<ProbeOutcome.Success>(outcome)
        assertEquals(probePaymentA, outcome.paymentId)
        assertEquals(probeHashA, outcome.paymentHash)
    }

    @Test
    fun `waitForProbeOutcome returns cached success when event arrives before wait`() = test {
        val onEvent = startNodeAndCaptureEvents()
        onEvent(Event.ProbeSuccessful(paymentId = probePaymentA, paymentHash = probeHashA))

        val outcome = sut.waitForProbeOutcome(setOf(probePaymentA)).getOrThrow()

        assertIs<ProbeOutcome.Success>(outcome)
        assertEquals(probePaymentA, outcome.paymentId)
        assertEquals(probeHashA, outcome.paymentHash)
    }

    @Test
    fun `waitForProbeOutcome returns last failure only after all tracked probes fail`() = test {
        val onEvent = startNodeAndCaptureEvents()
        val result = async { sut.waitForProbeOutcome(setOf(probePaymentA, probePaymentB)) }

        onEvent(Event.ProbeFailed(paymentId = probePaymentA, paymentHash = probeHashA, shortChannelId = 1uL))
        onEvent(Event.ProbeFailed(paymentId = probePaymentB, paymentHash = probeHashB, shortChannelId = 2uL))

        val outcome = result.await().getOrThrow()
        assertIs<ProbeOutcome.Failure>(outcome)
        assertEquals(probePaymentB, outcome.paymentId)
        assertEquals(probeHashB, outcome.paymentHash)
        assertEquals(2uL, outcome.shortChannelId)
    }

    @Test
    fun `waitForProbeOutcome returns first success even when another path already failed`() = test {
        val onEvent = startNodeAndCaptureEvents()
        val result = async { sut.waitForProbeOutcome(setOf(probePaymentA, probePaymentB)) }

        onEvent(Event.ProbeFailed(paymentId = probePaymentA, paymentHash = probeHashA, shortChannelId = 1uL))
        onEvent(Event.ProbeSuccessful(paymentId = probePaymentB, paymentHash = probeHashB))

        val outcome = result.await().getOrThrow()
        assertIs<ProbeOutcome.Success>(outcome)
        assertEquals(probePaymentB, outcome.paymentId)
        assertEquals(probeHashB, outcome.paymentHash)
    }

    @Test
    fun `waitForProbeOutcome does not hang on partial cached failures`() = test {
        val onEvent = startNodeAndCaptureEvents()
        onEvent(Event.ProbeFailed(paymentId = probePaymentA, paymentHash = probeHashA, shortChannelId = 1uL))

        val result = async { sut.waitForProbeOutcome(setOf(probePaymentA, probePaymentB)) }
        onEvent(Event.ProbeFailed(paymentId = probePaymentB, paymentHash = probeHashB, shortChannelId = 2uL))

        val outcome = result.await().getOrThrow()
        assertIs<ProbeOutcome.Failure>(outcome)
        assertEquals(probePaymentB, outcome.paymentId)
        assertEquals(2uL, outcome.shortChannelId)
    }

    @Test
    fun `waitForProbeOutcome returns timeout error when no matching event arrives`() = test {
        startNodeAndCaptureEvents()

        val result = sut.waitForProbeOutcome(setOf(probePaymentA), timeout = 1.seconds)

        assertTrue(result.isFailure)
        assertIs<ProbeError.TimedOut>(result.exceptionOrNull())
    }

    @Test
    fun `stop clears probe cache`() = test {
        val onEvent = startNodeAndCaptureEvents()
        whenever(lightningService.stop()).thenReturn(Unit)
        onEvent(Event.ProbeSuccessful(paymentId = probePaymentA, paymentHash = probeHashA))

        sut.stop()
        val result = sut.waitForProbeOutcome(setOf(probePaymentA), timeout = 1.seconds)

        assertTrue(result.isFailure)
        assertIs<ProbeError.TimedOut>(result.exceptionOrNull())
    }

    @Test
    fun `sendProbeForInvoice returns ProbeDispatch with payment IDs`() = test {
        startNodeForTesting()
        whenever(lightningService.sendProbes("lnbc1")).thenReturn(Result.success(setOf(probePaymentA, probePaymentB)))

        val result = sut.sendProbeForInvoice("lnbc1")

        assertTrue(result.isSuccess)
        assertEquals(setOf(probePaymentA, probePaymentB), result.getOrThrow().paymentIds)
    }

    @Test
    fun `sendProbeForNode delegates to keysend probe and returns payment IDs`() = test {
        startNodeForTesting()
        whenever(lightningService.sendKeysendProbe(probeNodeId, 42_000uL))
            .thenReturn(Result.success(setOf(probePaymentA)))

        val result = sut.sendProbeForNode(probeNodeId, amountSats = 42uL)

        assertTrue(result.isSuccess)
        assertEquals(setOf(probePaymentA), result.getOrThrow().paymentIds)
        verifyBlocking(lightningService) { sendKeysendProbe(probeNodeId, 42_000uL) }
    }

    @Test
    fun `start should not retry when node lifecycle state is Running`() = test {
        sut.setInitNodeLifecycleState()
        whenever(lightningService.node).thenReturn(null)
        val blocktank = mock<BlocktankService>()
        whenever(coreService.blocktank).thenReturn(blocktank)
        whenever(blocktank.info(any())).thenReturn(null)

        // lightningService.start() succeeds (state becomes Running at line 241)
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
