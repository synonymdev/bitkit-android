package to.bitkit.ui.screens.trezor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.repositories.TrezorRepo
import to.bitkit.repositories.TrezorState
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.synonym.bitkitcore.Network as BitkitCoreNetwork

@OptIn(ExperimentalCoroutinesApi::class)
class TrezorViewModelTest : BaseUnitTest() {

    private val trezorRepo = mock<TrezorRepo>()

    private lateinit var viewModel: TrezorViewModel

    @Before
    fun setup() = runBlocking {
        val stateFlow = MutableStateFlow(TrezorState())
        whenever(trezorRepo.state).thenReturn(stateFlow.asStateFlow())
        whenever(trezorRepo.needsPairingCode).thenReturn(MutableStateFlow(false).asStateFlow())

        viewModel = TrezorViewModel(
            bgDispatcher = testDispatcher,
            trezorRepo = trezorRepo,
        )
    }

    // region Initial State

    @Test
    fun `initial uiState should have default derivation path`() {
        val state = viewModel.uiState.value

        assertTrue(state.derivationPath.startsWith("m/84'/"))
        assertTrue(state.derivationPath.endsWith("/0/0"))
    }

    @Test
    fun `initial uiState should have addressIndex 0`() {
        val state = viewModel.uiState.value

        assertEquals(0, state.addressIndex)
    }

    @Test
    fun `initial uiState should not be in any loading state`() {
        val state = viewModel.uiState.value

        assertFalse(state.isGettingAddress)
        assertFalse(state.isGettingPublicKey)
        assertFalse(state.isSigningMessage)
        assertFalse(state.isVerifyingMessage)
        assertFalse(state.isLookingUp)
        assertFalse(state.isComposing)
        assertFalse(state.isSigning)
        assertFalse(state.isBroadcasting)
    }

    @Test
    fun `initial uiState should have empty lookup input`() {
        val state = viewModel.uiState.value

        assertEquals("", state.lookupInput)
    }

    @Test
    fun `initial uiState should have no results`() {
        val state = viewModel.uiState.value

        assertNull(state.accountInfoResult)
        assertNull(state.addressInfoResult)
        assertNull(state.lastSignature)
        assertNull(state.lastSigningAddress)
        assertNull(state.precomposedResult)
        assertNull(state.signedTxResult)
        assertNull(state.broadcastTxid)
    }

    @Test
    fun `initial uiState should have FORM send step`() {
        val state = viewModel.uiState.value

        assertEquals(SendStep.FORM, state.sendStep)
    }

    // endregion

    // region Network Selection

    @Test
    fun `setSelectedNetwork to BITCOIN should update coin type to 0`() {
        viewModel.setSelectedNetwork(BitkitCoreNetwork.BITCOIN)

        val state = viewModel.uiState.value
        assertEquals(BitkitCoreNetwork.BITCOIN, state.selectedNetwork)
        assertEquals("m/84'/0'/0'/0/0", state.derivationPath)
    }

    @Test
    fun `setSelectedNetwork to TESTNET should update coin type to 1`() {
        viewModel.setSelectedNetwork(BitkitCoreNetwork.TESTNET)

        val state = viewModel.uiState.value
        assertEquals(BitkitCoreNetwork.TESTNET, state.selectedNetwork)
        assertEquals("m/84'/1'/0'/0/0", state.derivationPath)
    }

    @Test
    fun `setSelectedNetwork to REGTEST should update coin type to 1`() {
        viewModel.setSelectedNetwork(BitkitCoreNetwork.REGTEST)

        val state = viewModel.uiState.value
        assertEquals(BitkitCoreNetwork.REGTEST, state.selectedNetwork)
        assertEquals("m/84'/1'/0'/0/0", state.derivationPath)
    }

    @Test
    fun `setSelectedNetwork should reset addressIndex to 0`() {
        viewModel.incrementAddressIndex()
        viewModel.incrementAddressIndex()
        assertEquals(2, viewModel.uiState.value.addressIndex)

        viewModel.setSelectedNetwork(BitkitCoreNetwork.TESTNET)

        assertEquals(0, viewModel.uiState.value.addressIndex)
    }

    // endregion

    // region Address Index

    @Test
    fun `incrementAddressIndex should increment index and update path`() {
        viewModel.setSelectedNetwork(BitkitCoreNetwork.BITCOIN)

        viewModel.incrementAddressIndex()

        val state = viewModel.uiState.value
        assertEquals(1, state.addressIndex)
        assertEquals("m/84'/0'/0'/0/1", state.derivationPath)
    }

    @Test
    fun `incrementAddressIndex multiple times should increment correctly`() {
        viewModel.setSelectedNetwork(BitkitCoreNetwork.BITCOIN)

        viewModel.incrementAddressIndex()
        viewModel.incrementAddressIndex()
        viewModel.incrementAddressIndex()

        val state = viewModel.uiState.value
        assertEquals(3, state.addressIndex)
        assertEquals("m/84'/0'/0'/0/3", state.derivationPath)
    }

    // endregion

    // region Derivation Path

    @Test
    fun `setDerivationPath should update path`() {
        viewModel.setDerivationPath("m/49'/0'/0'/0/0")

        assertEquals("m/49'/0'/0'/0/0", viewModel.uiState.value.derivationPath)
    }

    // endregion

    // region Message Signing State

    @Test
    fun `setMessageToSign should update message`() {
        viewModel.setMessageToSign("Test message")

        assertEquals("Test message", viewModel.uiState.value.messageToSign)
    }

    @Test
    fun `initial message should be default greeting`() {
        assertEquals("Hello, Trezor!", viewModel.uiState.value.messageToSign)
    }

    // endregion

    // region Lookup Input

    @Test
    fun `setLookupInput should update input`() {
        viewModel.setLookupInput("xpub6C...")

        assertEquals("xpub6C...", viewModel.uiState.value.lookupInput)
    }

    // endregion

    // region Send Flow State

    @Test
    fun `setSendAddress should update address`() {
        viewModel.setSendAddress("bc1qtest...")

        assertEquals("bc1qtest...", viewModel.uiState.value.sendAddress)
    }

    @Test
    fun `setSendAmount should update amount`() {
        viewModel.setSendAmount("50000")

        assertEquals("50000", viewModel.uiState.value.sendAmountSats)
    }

    @Test
    fun `setSendFeeRate should update fee rate`() {
        viewModel.setSendFeeRate("10")

        assertEquals("10", viewModel.uiState.value.sendFeeRate)
    }

    @Test
    fun `toggleSendMax should toggle isSendMax`() {
        assertFalse(viewModel.uiState.value.isSendMax)

        viewModel.toggleSendMax()
        assertTrue(viewModel.uiState.value.isSendMax)

        viewModel.toggleSendMax()
        assertFalse(viewModel.uiState.value.isSendMax)
    }

    @Test
    fun `resetSendFlow should clear all send fields`() {
        viewModel.setSendAddress("bc1qtest")
        viewModel.setSendAmount("50000")
        viewModel.setSendFeeRate("10")
        viewModel.toggleSendMax()

        viewModel.resetSendFlow()

        val state = viewModel.uiState.value
        assertEquals("", state.sendAddress)
        assertEquals("", state.sendAmountSats)
        assertEquals("2", state.sendFeeRate)
        assertFalse(state.isSendMax)
        assertFalse(state.isComposing)
        assertFalse(state.isSigning)
        assertNull(state.precomposedResult)
        assertNull(state.signedTxResult)
        assertEquals(SendStep.FORM, state.sendStep)
        assertNull(state.broadcastTxid)
    }

    @Test
    fun `backToComposeForm should reset to FORM step`() {
        viewModel.backToComposeForm()

        val state = viewModel.uiState.value
        assertEquals(SendStep.FORM, state.sendStep)
        assertNull(state.precomposedResult)
        assertNull(state.signedTxResult)
    }

    // endregion
}
