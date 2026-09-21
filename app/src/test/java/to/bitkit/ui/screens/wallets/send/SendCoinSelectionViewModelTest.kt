package to.bitkit.ui.screens.wallets.send

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.OutPoint
import org.lightningdevkit.ldknode.SpendableUtxo
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SendCoinSelectionViewModelTest : BaseUnitTest() {
    private companion object {
        const val ADDRESS = "bcrt1qaddress"
        const val REQUIRED_AMOUNT = 10_000uL
        const val FEE = 500uL

        fun utxo(txid: String, valueSats: ULong, vout: UInt = 0u) = SpendableUtxo(
            outpoint = OutPoint(txid = txid, vout = vout),
            valueSats = valueSats,
        )

        val LARGE_UTXO = utxo(txid = "large", valueSats = 50_000uL)
        val MEDIUM_UTXO = utxo(txid = "medium", valueSats = 20_000uL)
        val SMALL_UTXO = utxo(txid = "small", valueSats = 5_000uL)
    }

    private val lightningRepo = mock<LightningRepo>()
    private val activityRepo = mock<ActivityRepo>()

    private lateinit var sut: SendCoinSelectionViewModel

    @Before
    fun setUp() {
        sut = SendCoinSelectionViewModel(
            bgDispatcher = testDispatcher,
            lightningRepo = lightningRepo,
            activityRepo = activityRepo,
        )
    }

    @Test
    fun `loadUtxos selects all utxos sorted by value and computes totals`() = test {
        loadUtxos(utxos = listOf(SMALL_UTXO, LARGE_UTXO, MEDIUM_UTXO))

        val state = sut.uiState.value
        val expected = listOf(LARGE_UTXO, MEDIUM_UTXO, SMALL_UTXO)
        assertEquals(expected, state.availableUtxos)
        assertEquals(expected, state.selectedUtxos)
        assertEquals(REQUIRED_AMOUNT + FEE, state.totalRequiredSat)
        assertEquals(LARGE_UTXO.valueSats + MEDIUM_UTXO.valueSats + SMALL_UTXO.valueSats, state.totalSelectedSat)
        assertTrue(state.isSelectionValid)
        verify(lightningRepo).calculateTotalFee(
            amountSats = eq(REQUIRED_AMOUNT),
            address = eq(ADDRESS),
            speed = anyOrNull(),
            utxosToSpend = eq(expected),
            feeRates = anyOrNull(),
        )
    }

    @Test
    fun `onToggleUtxo deselects utxo and invalidates selection below total required`() = test {
        loadUtxos(utxos = listOf(LARGE_UTXO, SMALL_UTXO))

        sut.onToggleUtxo(LARGE_UTXO)

        val state = sut.uiState.value
        assertEquals(listOf(SMALL_UTXO), state.selectedUtxos)
        assertEquals(SMALL_UTXO.valueSats, state.totalSelectedSat)
        assertFalse(state.isSelectionValid)
    }

    @Test
    fun `onToggleUtxo reselects utxo and restores valid selection`() = test {
        loadUtxos(utxos = listOf(LARGE_UTXO, SMALL_UTXO))

        sut.onToggleUtxo(LARGE_UTXO)
        sut.onToggleUtxo(LARGE_UTXO)

        val state = sut.uiState.value
        assertEquals(setOf(LARGE_UTXO, SMALL_UTXO), state.selectedUtxos.toSet())
        assertEquals(LARGE_UTXO.valueSats + SMALL_UTXO.valueSats, state.totalSelectedSat)
        assertTrue(state.isSelectionValid)
    }

    @Test
    fun `onToggleUtxo keeps selection valid when remaining utxos cover total required`() = test {
        loadUtxos(utxos = listOf(LARGE_UTXO, SMALL_UTXO))

        sut.onToggleUtxo(SMALL_UTXO)

        val state = sut.uiState.value
        assertEquals(listOf(LARGE_UTXO), state.selectedUtxos)
        assertEquals(LARGE_UTXO.valueSats, state.totalSelectedSat)
        assertTrue(state.isSelectionValid)
    }

    @Test
    fun `selection is invalid when total required is below dust limit`() = test {
        loadUtxos(utxos = listOf(LARGE_UTXO), requiredAmount = 100uL, fee = 100uL)

        val state = sut.uiState.value
        assertEquals(200uL, state.totalRequiredSat)
        assertFalse(state.isSelectionValid)
    }

    private suspend fun TestScope.loadUtxos(
        utxos: List<SpendableUtxo>,
        requiredAmount: ULong = REQUIRED_AMOUNT,
        fee: ULong = FEE,
    ) {
        whenever(lightningRepo.listSpendableOutputs()).thenReturn(Result.success(utxos))
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(fee))
        sut.loadUtxos(requiredAmount, ADDRESS)
        advanceUntilIdle()
    }
}
