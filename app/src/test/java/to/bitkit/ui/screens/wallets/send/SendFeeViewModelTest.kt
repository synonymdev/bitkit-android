package to.bitkit.ui.screens.wallets.send

import android.content.Context
import com.synonym.bitkitcore.FeeRates
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import to.bitkit.models.BalanceState
import to.bitkit.models.FeeRate
import to.bitkit.models.TransactionSpeed
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.viewmodels.SendUiState
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendFeeViewModelTest : BaseUnitTest() {

    private lateinit var sut: SendFeeViewModel
    private val lightningRepo: LightningRepo = mock()
    private val currencyRepo: CurrencyRepo = mock()
    private val walletRepo: WalletRepo = mock()
    private val context: Context = mock()

    private val balance = 100_000uL
    private val fee = 1_000uL

    @Before
    fun setUp() {
        whenever(context.getString(any())).thenReturn("text")

        wheneverBlocking { lightningRepo.calculateTotalFee(any(), any(), any(), any(), any()) }
            .thenReturn(Result.success(fee))

        whenever(walletRepo.balanceState)
            .thenReturn(MutableStateFlow(BalanceState(totalOnchainSats = balance)))

        sut = SendFeeViewModel(lightningRepo, currencyRepo, walletRepo, context)
    }

    @Test
    fun `init should disable rates with fees exceeding max`() = test {
        val sendUiState = createSendUiState(
            amount = 80_000uL, // Max fee = min(50k, 20k) = 20k
            fees = mapOf(
                FeeRate.FAST to 25_000L,
                FeeRate.NORMAL to 15_000L,
                FeeRate.SLOW to 10_000L,
                FeeRate.CUSTOM to 0L
            )
        )

        sut.init(sendUiState)

        assertTrue(sut.uiState.value.disabledRates.contains(FeeRate.FAST))
        assertTrue(sut.uiState.value.disabledRates.size == 1)
    }

    @Test
    fun `init should not disable rates when all fees are affordable`() = test {
        val sendUiState = createSendUiState(
            amount = 10_000uL, // Max fee = min(50k, 90k) = 50k
            fees = mapOf(
                FeeRate.FAST to 40_000L,
                FeeRate.NORMAL to 30_000L,
                FeeRate.SLOW to 20_000L,
                FeeRate.CUSTOM to 0L
            )
        )

        sut.init(sendUiState)

        assertTrue(sut.uiState.value.disabledRates.isEmpty())
    }

    @Test
    fun `validateCustomFee should return false when exceeding max rate`() = test {
        sut.init(createSendUiState())
        sut.onKeyPress(KEY_DELETE)
        sut.onKeyPress("1")
        sut.onKeyPress("0")
        sut.onKeyPress("0")

        sut.validateCustomFee()

        assertFalse(sut.uiState.value.shouldContinue == true)
    }

    @Test
    fun `validateCustomFee should return false when fee is below minimum rate`() = test {
        sut.init(createSendUiState())
        sut.onKeyPress(KEY_DELETE)
        sut.onKeyPress("1")

        sut.validateCustomFee()

        assertFalse(sut.uiState.value.shouldContinue == true)
    }

    @Test
    fun `validateCustomFee should return true when fee calculation fails (999 fallback)`() = test {
        whenever(lightningRepo.calculateTotalFee(any(), any(), any(), any(), any()))
            .thenReturn(Result.success(0uL))
        sut.init(createSendUiState())
        sut.onKeyPress(KEY_DELETE)
        sut.onKeyPress("9")
        sut.onKeyPress("9")
        sut.onKeyPress("9")

        sut.validateCustomFee()

        assertTrue(sut.uiState.value.shouldContinue == true)
    }

    private fun createSendUiState(
        amount: ULong = 10_000uL,
        address: String = "address",
        fees: Map<FeeRate, Long> = mapOf(
            FeeRate.FAST to 4_000L,
            FeeRate.NORMAL to 3_000L,
            FeeRate.SLOW to 2_000L,
            FeeRate.CUSTOM to 0L
        ),
    ) = SendUiState(
        amount = amount,
        selectedUtxos = persistentListOf(),
        address = address,
        speed = TransactionSpeed.Medium,
        feeRates = FeeRates(fast = 10u, mid = 5u, slow = 2u),
        fees = fees.toImmutableMap(),
    )
}
