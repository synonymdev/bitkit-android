package to.bitkit.ui.screens.wallets.receive

import android.content.Context
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.HwFundingAddressType
import to.bitkit.models.HwReceiveAddress
import to.bitkit.models.HwWallet
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HwReceiveViewModelTest : BaseUnitTest() {

    private val context = mock<Context>()
    private val hwWalletRepo = mock<HwWalletRepo>()
    private val wallets = MutableStateFlow(persistentListOf<HwWallet>())
    private val receiveAddress = MutableStateFlow<HwReceiveAddress?>(null)

    private lateinit var sut: HwReceiveViewModel

    @Before
    fun setUp() {
        whenever(hwWalletRepo.wallets).thenReturn(wallets)
        whenever(hwWalletRepo.observeReceiveAddress(any(), any())).thenReturn(receiveAddress)
        sut = HwReceiveViewModel(context, hwWalletRepo)
    }

    @Test
    fun `loads the next unused hardware address`() = test {
        whenever(hwWalletRepo.getReceiveAddress(WALLET_ID)).thenReturn(Result.success(RECEIVE_ADDRESS))

        sut.loadAddress(WALLET_ID)
        advanceUntilIdle()

        assertEquals(RECEIVE_ADDRESS, sut.uiState.value.address)
        assertFalse(sut.uiState.value.isLoadingAddress)
        assertFalse(sut.uiState.value.addressLoadFailed)
    }

    @Test
    fun `retry loads the hardware address after a failure`() = test {
        whenever(hwWalletRepo.getReceiveAddress(WALLET_ID)).thenReturn(
            Result.failure(AppError("address unavailable")),
            Result.success(RECEIVE_ADDRESS),
        )

        sut.loadAddress(WALLET_ID)
        advanceUntilIdle()

        assertTrue(sut.uiState.value.addressLoadFailed)

        sut.retryAddress()
        advanceUntilIdle()

        assertEquals(RECEIVE_ADDRESS, sut.uiState.value.address)
        assertFalse(sut.uiState.value.isLoadingAddress)
        assertFalse(sut.uiState.value.addressLoadFailed)
        verify(hwWalletRepo, times(2)).getReceiveAddress(WALLET_ID)
    }

    @Test
    fun `passphrase reconnect resumes address verification`() = test {
        whenever(hwWalletRepo.getReceiveAddress(WALLET_ID)).thenReturn(Result.success(RECEIVE_ADDRESS))
        whenever(hwWalletRepo.needsPassphrase(WALLET_ID)).thenReturn(true, false)
        whenever(hwWalletRepo.reconnectWithPassphrase(WALLET_ID, PASSPHRASE))
            .thenReturn(Result.success(Unit))
        whenever(hwWalletRepo.verifyReceiveAddress(WALLET_ID, RECEIVE_ADDRESS))
            .thenReturn(Result.success(Unit))
        sut.loadAddress(WALLET_ID)
        advanceUntilIdle()

        sut.verifyAddress()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isPassphraseRequired)

        sut.submitPassphrase(PASSPHRASE)
        advanceUntilIdle()

        verify(hwWalletRepo).reconnectWithPassphrase(WALLET_ID, PASSPHRASE)
        verify(hwWalletRepo).verifyReceiveAddress(WALLET_ID, RECEIVE_ADDRESS)
        assertFalse(sut.uiState.value.isPassphraseRequired)
        assertFalse(sut.uiState.value.isVerifyingAddress)
    }

    @Test
    fun `cancel clears the cached receive address`() = test {
        whenever(hwWalletRepo.getReceiveAddress(WALLET_ID)).thenReturn(Result.success(RECEIVE_ADDRESS))
        sut.loadAddress(WALLET_ID)
        advanceUntilIdle()

        sut.cancel()

        assertEquals(HwReceiveUiState(), sut.uiState.value)
    }

    @Test
    fun `updates the displayed address when the watcher advances`() = test {
        whenever(hwWalletRepo.getReceiveAddress(WALLET_ID)).thenReturn(Result.success(RECEIVE_ADDRESS))
        sut.loadAddress(WALLET_ID)
        advanceUntilIdle()

        receiveAddress.value = NEXT_RECEIVE_ADDRESS
        advanceUntilIdle()

        assertEquals(NEXT_RECEIVE_ADDRESS, sut.uiState.value.address)
        assertFalse(sut.uiState.value.addressLoadFailed)
    }

    @Test
    fun `initial load does not replace a newer watcher address`() = test {
        val loadResult = CompletableDeferred<Result<HwReceiveAddress>>()
        whenever(hwWalletRepo.getReceiveAddress(WALLET_ID)).doSuspendableAnswer { loadResult.await() }

        sut.loadAddress(WALLET_ID)
        advanceUntilIdle()

        receiveAddress.value = NEXT_RECEIVE_ADDRESS
        advanceUntilIdle()
        loadResult.complete(Result.success(RECEIVE_ADDRESS))
        advanceUntilIdle()

        assertEquals(NEXT_RECEIVE_ADDRESS, sut.uiState.value.address)
        assertFalse(sut.uiState.value.isLoadingAddress)
        assertFalse(sut.uiState.value.addressLoadFailed)
    }

    @Test
    fun `watcher address change cancels active verification`() = test {
        val verificationStarted = CompletableDeferred<Unit>()
        val verificationResult = CompletableDeferred<Result<Unit>>()
        whenever(hwWalletRepo.getReceiveAddress(WALLET_ID)).thenReturn(Result.success(RECEIVE_ADDRESS))
        whenever(hwWalletRepo.needsPassphrase(WALLET_ID)).thenReturn(false)
        whenever(hwWalletRepo.verifyReceiveAddress(WALLET_ID, RECEIVE_ADDRESS)).doSuspendableAnswer {
            verificationStarted.complete(Unit)
            verificationResult.await()
        }
        sut.loadAddress(WALLET_ID)
        advanceUntilIdle()

        sut.verifyAddress()
        verificationStarted.await()
        assertTrue(sut.uiState.value.isVerifyingAddress)

        receiveAddress.value = NEXT_RECEIVE_ADDRESS
        advanceUntilIdle()

        assertEquals(NEXT_RECEIVE_ADDRESS, sut.uiState.value.address)
        assertFalse(sut.uiState.value.isVerifyingAddress)
    }

    private companion object {
        const val WALLET_ID = "trezor:wallet"
        const val PASSPHRASE = "hidden wallet"
        val RECEIVE_ADDRESS = HwReceiveAddress(
            address = "bcrt1qs04g2ka4pr9s3mv73nu32tvfy7r3cxd27wkyu8",
            path = "m/84'/1'/0'/0/0",
            addressType = HwFundingAddressType.NATIVE_SEGWIT,
        )
        val NEXT_RECEIVE_ADDRESS = RECEIVE_ADDRESS.copy(
            address = "bcrt1q9u8ep0ux8qll9z8vx22n3u9aetlw6ae05kgj9q",
            path = "m/84'/1'/0'/0/1",
        )
    }
}
