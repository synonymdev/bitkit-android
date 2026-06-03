package to.bitkit.ui.screens.trezor

import com.synonym.bitkitcore.TrezorSignedTx
import com.synonym.bitkitcore.WatcherEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.repositories.TrezorRepo
import to.bitkit.repositories.TrezorState
import to.bitkit.services.TrezorWalletMode
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.synonym.bitkitcore.Network as BitkitCoreNetwork

@OptIn(ExperimentalCoroutinesApi::class)
class TrezorViewModelTest : BaseUnitTest() {

    private val trezorRepo: TrezorRepo = mock()
    private val trezorStateFlow = MutableStateFlow(TrezorState())
    private val needsPairingCodeFlow = MutableStateFlow(false)
    private val needsPinEntryFlow = MutableStateFlow(false)
    private val walletModeFlow = MutableStateFlow(TrezorWalletMode.STANDARD)
    private val watcherEventsFlow = MutableSharedFlow<Pair<String, WatcherEvent>>()

    private lateinit var sut: TrezorViewModel

    @Before
    fun setUp() {
        whenever(trezorRepo.state).thenReturn(trezorStateFlow)
        whenever(trezorRepo.needsPairingCode).thenReturn(needsPairingCodeFlow)
        whenever(trezorRepo.needsPinEntry).thenReturn(needsPinEntryFlow)
        whenever(trezorRepo.walletMode).thenReturn(walletModeFlow)
        whenever(trezorRepo.watcherEvents).thenReturn(watcherEventsFlow)
        whenever(trezorRepo.observeExternalDisconnects(any())).then { }
        sut = createViewModel()
    }

    // region Pure state setters

    @Test
    fun `setDerivationPath should update uiState derivationPath`() {
        val path = "m/49'/0'/0'/0/0"
        sut.setDerivationPath(path)

        assertEquals(path, sut.uiState.value.derivationPath)
    }

    @Test
    fun `setSelectedNetwork to BITCOIN should use coinType 0 in path`() {
        sut.setSelectedNetwork(BitkitCoreNetwork.BITCOIN)

        val state = sut.uiState.value
        assertEquals(BitkitCoreNetwork.BITCOIN, state.selectedNetwork)
        assertEquals("m/84'/0'/0'/0/0", state.derivationPath)
        assertEquals(0, state.addressIndex)
    }

    @Test
    fun `setSelectedNetwork to TESTNET should use coinType 1 in path`() {
        sut.setSelectedNetwork(BitkitCoreNetwork.TESTNET)

        val state = sut.uiState.value
        assertEquals(BitkitCoreNetwork.TESTNET, state.selectedNetwork)
        assertEquals("m/84'/1'/0'/0/0", state.derivationPath)
        assertEquals(0, state.addressIndex)
    }

    @Test
    fun `incrementAddressIndex should increment index and update path`() {
        sut.setSelectedNetwork(BitkitCoreNetwork.BITCOIN)
        sut.incrementAddressIndex()

        val state = sut.uiState.value
        assertEquals(1, state.addressIndex)
        assertEquals("m/84'/0'/0'/0/1", state.derivationPath)
    }

    @Test
    fun `setMessageToSign should update messageToSign`() {
        val message = "Test message"
        sut.setMessageToSign(message)

        assertEquals(message, sut.uiState.value.messageToSign)
    }

    @Test
    fun `setSendAddress should update sendAddress`() {
        val address = "bc1qtest123"
        sut.setSendAddress(address)

        assertEquals(address, sut.uiState.value.sendAddress)
    }

    @Test
    fun `setSendAmount should update sendAmountSats`() {
        val amount = "50000"
        sut.setSendAmount(amount)

        assertEquals(amount, sut.uiState.value.sendAmountSats)
    }

    @Test
    fun `setSendFeeRate should update sendFeeRate`() {
        val feeRate = "10"
        sut.setSendFeeRate(feeRate)

        assertEquals(feeRate, sut.uiState.value.sendFeeRate)
    }

    @Test
    fun `toggleSendMax should toggle isSendMax`() {
        assertFalse(sut.uiState.value.isSendMax)

        sut.toggleSendMax()
        assertTrue(sut.uiState.value.isSendMax)

        sut.toggleSendMax()
        assertFalse(sut.uiState.value.isSendMax)
    }

    @Test
    fun `resetSendFlow should clear all send-related state`() {
        sut.setSendAddress("bc1qtest")
        sut.setSendAmount("10000")
        sut.setSendFeeRate("5")
        sut.toggleSendMax()

        sut.resetSendFlow()

        val state = sut.uiState.value
        assertEquals("", state.sendAddress)
        assertEquals("", state.sendAmountSats)
        assertEquals("2", state.sendFeeRate)
        assertFalse(state.isSendMax)
        assertFalse(state.isComposing)
        assertFalse(state.isSigning)
        assertNull(state.composeResult)
        assertNull(state.signedTxResult)
        assertEquals(SendStep.Form, state.sendStep)
        assertFalse(state.isBroadcasting)
        assertNull(state.broadcastTxid)
    }

    @Test
    fun `setLookupInput should update lookupInput`() {
        val input = "xpub6test123"
        sut.setLookupInput(input)

        assertEquals(input, sut.uiState.value.lookupInput)
    }

    // endregion

    // region Async methods

    @Test
    fun `initialize should call trezorRepo initialize`() = test {
        whenever(trezorRepo.initialize()).thenReturn(Result.success(Unit))

        sut.initialize()
        advanceUntilIdle()

        verify(trezorRepo).initialize()
    }

    @Test
    fun `scan should call trezorRepo scan`() = test {
        whenever(trezorRepo.scan()).thenReturn(Result.success(emptyList()))

        sut.scan()
        advanceUntilIdle()

        verify(trezorRepo).scan()
    }

    @Test
    fun `connect should reset wallet selection then call trezorRepo connect with deviceId`() = test {
        val deviceId = "device-123"
        whenever(trezorRepo.connect(deviceId)).thenReturn(Result.failure(RuntimeException("test")))

        sut.connect(deviceId)
        advanceUntilIdle()

        // An explicit device pick starts from the standard wallet, so the reset
        // must happen before the connect.
        inOrder(trezorRepo) {
            verify(trezorRepo).resetWalletSelection()
            verify(trezorRepo).connect(deviceId)
        }
    }

    @Test
    fun `connectKnownDevice should reset wallet selection then call trezorRepo connectKnownDevice`() = test {
        val deviceId = "device-123"
        whenever(trezorRepo.connectKnownDevice(deviceId)).thenReturn(Result.failure(RuntimeException("test")))

        sut.connectKnownDevice(deviceId)
        advanceUntilIdle()

        inOrder(trezorRepo) {
            verify(trezorRepo).resetWalletSelection()
            verify(trezorRepo).connectKnownDevice(deviceId)
        }
    }

    @Test
    fun `disconnect should call trezorRepo disconnect`() = test {
        whenever(trezorRepo.disconnect()).thenReturn(Result.success(Unit))

        sut.disconnect()
        advanceUntilIdle()

        verify(trezorRepo).disconnect()
    }

    @Test
    fun `getAddress should clear isGettingAddress on success`() = test {
        whenever(trezorRepo.getAddress(any(), any(), any(), any()))
            .thenReturn(Result.success(mock()))

        sut.getAddress()
        advanceUntilIdle()

        assertFalse(sut.uiState.value.isGettingAddress)
    }

    @Test
    fun `getAddress should clear isGettingAddress on failure`() = test {
        whenever(trezorRepo.getAddress(any(), any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("test")))

        sut.getAddress()
        advanceUntilIdle()

        assertFalse(sut.uiState.value.isGettingAddress)
    }

    @Test
    fun `signMessage should not call repo when message is blank`() = test {
        sut.setMessageToSign("")

        sut.signMessage()
        advanceUntilIdle()

        verify(trezorRepo, never()).signMessage(any(), any(), any())
    }

    @Test
    fun `verifyMessage should not call repo when no signature exists`() = test {
        sut.verifyMessage()
        advanceUntilIdle()

        verify(trezorRepo, never()).verifyMessage(any(), any(), any(), any())
    }

    @Test
    fun `broadcastSignedTx should not call repo when no signedTxResult`() = test {
        sut.broadcastSignedTx()
        advanceUntilIdle()

        verify(trezorRepo, never()).broadcastRawTx(any(), any())
    }

    @Test
    fun `broadcastSignedTx should not restore signed step after reset`() = test {
        loadSignedTx()
        val broadcastResult = CompletableDeferred<Result<String>>()
        whenever(trezorRepo.broadcastRawTx(any(), any()))
            .doSuspendableAnswer { broadcastResult.await() }

        sut.broadcastSignedTx()
        assertTrue(sut.uiState.value.isBroadcasting)

        sut.resetSendFlow()
        broadcastResult.complete(Result.success("broadcast-txid"))
        advanceUntilIdle()

        val state = sut.uiState.value
        assertEquals(SendStep.Form, state.sendStep)
        assertFalse(state.isBroadcasting)
        assertNull(state.broadcastTxid)
    }

    @Test
    fun `broadcastSignedTx failure should not clear newer broadcast`() = test {
        loadSignedTx()
        val firstBroadcastResult = CompletableDeferred<Result<String>>()
        val secondBroadcastResult = CompletableDeferred<Result<String>>()
        val broadcastResults = ArrayDeque(
            listOf(firstBroadcastResult, secondBroadcastResult)
        )
        whenever(trezorRepo.broadcastRawTx(any(), any()))
            .doSuspendableAnswer { broadcastResults.removeFirst().await() }

        sut.broadcastSignedTx()
        assertTrue(sut.uiState.value.isBroadcasting)

        val secondSignedTx = TrezorSignedTx(
            signatures = listOf("30440220new"),
            serializedTx = "0200000001new",
            txid = "new-broadcast-txid",
        )
        sut.resetSendFlow()
        loadSignedTx(secondSignedTx)
        sut.broadcastSignedTx()
        assertTrue(sut.uiState.value.isBroadcasting)

        firstBroadcastResult.complete(Result.failure(RuntimeException("first failed")))
        advanceUntilIdle()

        val staleFailureState = sut.uiState.value
        assertEquals(secondSignedTx, staleFailureState.signedTxResult)
        assertTrue(staleFailureState.isBroadcasting)
        assertNull(staleFailureState.broadcastTxid)

        secondBroadcastResult.complete(Result.success("second-broadcast-txid"))
        advanceUntilIdle()

        val finalState = sut.uiState.value
        assertFalse(finalState.isBroadcasting)
        assertEquals("second-broadcast-txid", finalState.broadcastTxid)
    }

    @Test
    fun `composeTx should not call repo when destination address is blank`() = test {
        loadAccountInfo()
        sut.setSendAmount("1000")
        sut.setSendFeeRate("2")

        sut.composeTx()
        advanceUntilIdle()

        verify(trezorRepo, never()).composeTransaction(any(), any(), any(), any(), anyOrNull(), any())
    }

    @Test
    fun `composeTx should not call repo when fee rate is invalid`() = test {
        loadAccountInfo()
        sut.setSendAddress("bc1qtest123")
        sut.setSendAmount("1000")
        sut.setSendFeeRate("0")

        sut.composeTx()
        advanceUntilIdle()

        verify(trezorRepo, never()).composeTransaction(any(), any(), any(), any(), anyOrNull(), any())
    }

    @Test
    fun `signComposedTx should not call repo when no compose result exists`() = test {
        sut.signComposedTx()
        advanceUntilIdle()

        verify(trezorRepo, never()).signTxFromPsbt(any(), anyOrNull())
    }

    @Test
    fun `startWatcher should keep status starting until watcher event arrives`() = test {
        whenever(trezorRepo.startWatcher(any(), any(), any(), any()))
            .thenReturn(Result.success(Unit))
        sut.setWatcherExtendedKey("xpub6test123")

        sut.startWatcher()
        advanceUntilIdle()

        val state = sut.uiState.value
        assertFalse(state.isStartingWatcher)
        assertNotNull(state.activeWatcherId)
        assertEquals(WatcherConnectionStatus.STARTING, state.watcherConnectionStatus)
    }

    @Test
    fun `startWatcher should reject zero gap limit`() = test {
        sut.setWatcherExtendedKey("xpub6test123")
        sut.setWatcherGapLimit("0")

        sut.startWatcher()
        advanceUntilIdle()

        verify(trezorRepo, never()).startWatcher(any(), any(), any(), any())
        assertNull(sut.uiState.value.activeWatcherId)
    }

    @Test
    fun `watcher transaction event should mark watcher connected`() = test {
        whenever(trezorRepo.startWatcher(any(), any(), any(), any()))
            .thenReturn(Result.success(Unit))
        sut.setWatcherExtendedKey("xpub6test123")
        sut.startWatcher()
        advanceUntilIdle()
        val watcherId = assertNotNull(sut.uiState.value.activeWatcherId)

        watcherEventsFlow.emit(
            watcherId to WatcherEvent.TransactionsChanged(
                transactions = TrezorPreviewData.sampleHistoryTransactions,
                balance = TrezorPreviewData.sampleWalletBalance,
                txCount = 3u,
                blockHeight = 850_000u,
                accountType = TrezorPreviewData.sampleTransactionHistoryResult.accountType,
            ),
        )
        advanceUntilIdle()

        val state = sut.uiState.value
        assertEquals(WatcherConnectionStatus.CONNECTED, state.watcherConnectionStatus)
        assertEquals(TrezorPreviewData.sampleWalletBalance, state.watcherBalance)
        assertEquals(3u, state.watcherTransactionCount)
    }

    @Test
    fun `stopWatcher should stop repo watcher and clear watcher state`() = test {
        whenever(trezorRepo.startWatcher(any(), any(), any(), any()))
            .thenReturn(Result.success(Unit))
        whenever(trezorRepo.stopWatcher(any())).thenReturn(Result.success(Unit))
        sut.setWatcherExtendedKey("xpub6test123")
        sut.startWatcher()
        advanceUntilIdle()
        val watcherId = assertNotNull(sut.uiState.value.activeWatcherId)

        sut.stopWatcher()
        advanceUntilIdle()

        verify(trezorRepo).stopWatcher(watcherId)
        val state = sut.uiState.value
        assertNull(state.activeWatcherId)
        assertEquals(WatcherConnectionStatus.IDLE, state.watcherConnectionStatus)
        assertNull(state.watcherBalance)
        assertTrue(state.watcherTransactions.isEmpty())
    }

    @Test
    fun `clearError should call trezorRepo clearError`() {
        sut.clearError()

        verify(trezorRepo).clearError()
    }

    @Test
    fun `submitPairingCode should call trezorRepo submitPairingCode`() {
        val code = "123456"
        sut.submitPairingCode(code)

        verify(trezorRepo).submitPairingCode(code)
    }

    @Test
    fun `cancelPairingCode should call trezorRepo cancelPairingCode`() {
        sut.cancelPairingCode()

        verify(trezorRepo).cancelPairingCode()
    }

    @Test
    fun `hasKnownDevices should delegate to trezorRepo`() {
        whenever(trezorRepo.hasKnownDevices()).thenReturn(true)

        assertTrue(sut.hasKnownDevices())
        verify(trezorRepo).hasKnownDevices()
    }

    // endregion

    private fun createViewModel() = TrezorViewModel(
        bgDispatcher = testDispatcher,
        trezorRepo = trezorRepo,
    )

    private suspend fun TestScope.loadAccountInfo() {
        whenever(trezorRepo.getAccountInfo(any(), any(), anyOrNull()))
            .thenReturn(Result.success(TrezorPreviewData.sampleAccountInfoResult))

        sut.setLookupInput("xpub6test123")
        sut.lookupBalanceInfo()
        advanceUntilIdle()
    }

    private suspend fun TestScope.loadSignedTx(
        signedTx: TrezorSignedTx = TrezorPreviewData.sampleSignedTx,
    ) {
        loadAccountInfo()
        whenever(trezorRepo.composeTransaction(any(), any(), any(), any(), anyOrNull(), any()))
            .thenReturn(Result.success(listOf(TrezorPreviewData.sampleComposeResult)))
        whenever(trezorRepo.signTxFromPsbt(any(), anyOrNull()))
            .thenReturn(Result.success(signedTx))

        sut.setSendAddress("bc1qtest123")
        sut.setSendAmount("1000")
        sut.composeTx()
        advanceUntilIdle()
        sut.signComposedTx()
        advanceUntilIdle()
    }
}
