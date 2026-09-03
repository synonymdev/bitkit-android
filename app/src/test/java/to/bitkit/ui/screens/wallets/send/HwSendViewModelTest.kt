package to.bitkit.ui.screens.wallets.send

import android.content.Context
import com.synonym.bitkitcore.BroadcastException
import com.synonym.bitkitcore.TrezorException
import com.synonym.bitkitcore.TrezorFeatures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.HwFundingBroadcastResult
import to.bitkit.models.HwFundingSignedTx
import to.bitkit.models.HwFundingTransaction
import to.bitkit.models.Toast
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.PreActivityMetadataRepo
import to.bitkit.services.ActivityService
import to.bitkit.services.CoreService
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HwSendViewModelTest : BaseUnitTest() {

    private val context = mock<Context>()
    private val hwWalletRepo = mock<HwWalletRepo>()
    private val preActivityMetadataRepo = mock<PreActivityMetadataRepo>()
    private val coreService = mock<CoreService>()
    private val activityService = mock<ActivityService>()
    private val activityRepo = mock<ActivityRepo>()

    private lateinit var sut: HwSendViewModel

    @Before
    fun setUp() {
        whenever(coreService.activity).thenReturn(activityService)
        sut = HwSendViewModel(
            context = context,
            hwWalletRepo = hwWalletRepo,
            preActivityMetadataRepo = preActivityMetadataRepo,
            coreService = coreService,
            activityRepo = activityRepo,
        )
    }

    @Test
    fun `signing reconnects and retries once after THP channel failure`() = test {
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = 1_000uL,
            feeRate = 2.0f,
            totalSpent = 26_000uL,
            satsPerVByte = 2uL,
        )
        val signedTx = HwFundingSignedTx(
            serializedTx = "rawtx",
            miningFeeSats = funding.miningFeeSats,
            feeRate = 2uL,
            totalSpent = funding.totalSpent,
        )
        val broadcast = HwFundingBroadcastResult(
            txId = "txid",
            miningFeeSats = signedTx.miningFeeSats,
            feeRate = signedTx.feeRate,
            totalSpent = signedTx.totalSpent,
        )
        whenever(hwWalletRepo.needsPassphrase(WALLET_ID)).thenReturn(false)
        whenever(hwWalletRepo.ensureConnected(WALLET_ID)).thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(hwWalletRepo.composeFundingTransaction(WALLET_ID, ADDRESS, AMOUNT_SATS, SATS_PER_VBYTE))
            .thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(WALLET_ID, funding)).thenReturn(
            Result.failure(TrezorException.ProtocolException("THP decryption error: aead::Error")),
            Result.success(signedTx),
        )
        whenever(hwWalletRepo.broadcastFunding(signedTx)).thenReturn(Result.success(broadcast))

        sut.signAndBroadcast(
            HwSendRequest(
                walletId = WALLET_ID,
                address = ADDRESS,
                amountSats = AMOUNT_SATS,
                satsPerVByte = SATS_PER_VBYTE,
                tags = emptyList(),
            )
        )
        advanceUntilIdle()

        verify(hwWalletRepo, times(2)).ensureConnected(WALLET_ID)
        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, funding)
        verify(hwWalletRepo).broadcastFunding(signedTx)
        verify(activityService).createSentOnchainActivityFromSendResult(
            txid = broadcast.txId,
            address = ADDRESS,
            amount = AMOUNT_SATS,
            fee = broadcast.miningFeeSats,
            feeRate = broadcast.feeRate,
            isTransfer = false,
            channelId = null,
            walletId = WALLET_ID,
        )
        assertFalse(sut.uiState.value.isSigning)
    }

    @Test
    fun `contact preparation runs after signing and only once across broadcast retry`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(BroadcastException.ElectrumException("connection failed")),
            Result.success(fixture.broadcast),
        )
        var preparationCalls = 0
        val prepareContactPayment: suspend () -> Boolean = {
            verify(hwWalletRepo).signFunding(WALLET_ID, fixture.funding)
            verify(hwWalletRepo, never()).broadcastFunding(fixture.signedTx)
            preparationCalls += 1
            true
        }

        sut.signAndBroadcast(request(), prepareContactPayment)
        advanceUntilIdle()

        assertEquals(1, preparationCalls)
        assertTrue(sut.uiState.value.hasPendingBroadcast)

        sut.signAndBroadcast(request(), prepareContactPayment)
        advanceUntilIdle()

        assertEquals(1, preparationCalls)
        verify(hwWalletRepo).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, times(2)).broadcastFunding(fixture.signedTx)
        sut.completeBroadcast()
        assertFalse(sut.uiState.value.hasPendingBroadcast)
    }

    @Test
    fun `passphrase reconnect keeps contact preparation before broadcast`() = test {
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.needsPassphrase(WALLET_ID)).thenReturn(true, false)
        whenever(hwWalletRepo.reconnectWithPassphrase(WALLET_ID, "hidden wallet"))
            .thenReturn(Result.success(Unit))
        var preparationCalls = 0
        val prepareContactPayment: suspend () -> Boolean = {
            preparationCalls += 1
            true
        }

        sut.signAndBroadcast(request(), prepareContactPayment)
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isPassphraseRequired)

        sut.submitPassphrase(request(), "hidden wallet", prepareContactPayment)
        advanceUntilIdle()

        assertEquals(1, preparationCalls)
        verify(hwWalletRepo).broadcastFunding(fixture.signedTx)
        assertFalse(sut.uiState.value.isPassphraseRequired)
    }

    @Test
    fun `composition timeout shows payment timeout`() = test {
        val timeout = runCatching { withTimeout(0) { Unit } }.exceptionOrNull() as TimeoutCancellationException
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(hwWalletRepo.needsPassphrase(WALLET_ID)).thenReturn(false)
        whenever(hwWalletRepo.ensureConnected(WALLET_ID)).thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(hwWalletRepo.composeFundingTransaction(WALLET_ID, ADDRESS, AMOUNT_SATS, SATS_PER_VBYTE))
            .thenReturn(Result.failure(timeout))
        whenever(context.getString(R.string.common__error)).thenReturn("Error")
        whenever(context.getString(R.string.wallet__payment_timeout)).thenReturn("Payment timed out")

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(Toast.ToastType.ERROR, toasts.single().type)
        assertEquals("Payment timed out", toasts.single().description)
        assertFalse(sut.uiState.value.isSigning)
        verify(hwWalletRepo, never()).signFunding(any(), any())
        verify(hwWalletRepo, never()).broadcastFunding(any())
    }

    @Test
    fun `broadcast result survives collector reattachment until acknowledged`() = test {
        val fixture = stubSuccessfulPayment()

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertEquals(fixture.broadcast.txId, sut.results.first().txId)
        assertTrue(sut.uiState.value.isBroadcastUnresolved)

        sut.completeBroadcast()

        assertFalse(sut.uiState.value.isBroadcastUnresolved)
    }

    private suspend fun stubSuccessfulPayment(): PaymentFixture {
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = 1_000uL,
            feeRate = 2.0f,
            totalSpent = 26_000uL,
            satsPerVByte = SATS_PER_VBYTE,
        )
        val signedTx = HwFundingSignedTx(
            serializedTx = "rawtx",
            miningFeeSats = funding.miningFeeSats,
            feeRate = SATS_PER_VBYTE,
            totalSpent = funding.totalSpent,
        )
        val broadcast = HwFundingBroadcastResult(
            txId = "txid",
            miningFeeSats = signedTx.miningFeeSats,
            feeRate = signedTx.feeRate,
            totalSpent = signedTx.totalSpent,
        )
        whenever(hwWalletRepo.needsPassphrase(WALLET_ID)).thenReturn(false)
        whenever(hwWalletRepo.ensureConnected(WALLET_ID)).thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(hwWalletRepo.composeFundingTransaction(WALLET_ID, ADDRESS, AMOUNT_SATS, SATS_PER_VBYTE))
            .thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(WALLET_ID, funding)).thenReturn(Result.success(signedTx))
        whenever(hwWalletRepo.broadcastFunding(signedTx)).thenReturn(Result.success(broadcast))
        return PaymentFixture(funding, signedTx, broadcast)
    }

    private fun request() = HwSendRequest(
        walletId = WALLET_ID,
        address = ADDRESS,
        amountSats = AMOUNT_SATS,
        satsPerVByte = SATS_PER_VBYTE,
        tags = emptyList(),
    )

    private data class PaymentFixture(
        val funding: HwFundingTransaction,
        val signedTx: HwFundingSignedTx,
        val broadcast: HwFundingBroadcastResult,
    )

    private companion object {
        const val WALLET_ID = "hardware-wallet"
        const val ADDRESS = "bcrt1qs04g2ka4pr9s3mv73nu32tvfy7r3cxd27wkyu8"
        const val AMOUNT_SATS = 25_000uL
        const val SATS_PER_VBYTE = 2uL
    }
}
