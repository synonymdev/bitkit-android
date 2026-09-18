package to.bitkit.ui.screens.wallets.send

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.Toast
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.NodeNotRunningError
import to.bitkit.repositories.NodeRunTimeoutError
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AppError
import to.bitkit.utils.ServiceError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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

    @Test
    fun `loadUtxos retries when node is not setup and loads utxos on success`() = test {
        whenever(lightningRepo.listSpendableOutputs()).thenReturn(
            Result.failure(ServiceError.NodeNotSetup()),
            Result.success(listOf(SMALL_UTXO, LARGE_UTXO)),
        )
        stubFee()

        sut.loadUtxos(REQUIRED_AMOUNT, ADDRESS)
        advanceUntilIdle()

        val state = sut.uiState.value
        verify(lightningRepo, times(2)).listSpendableOutputs()
        assertEquals(listOf(LARGE_UTXO, SMALL_UTXO), state.availableUtxos)
        assertEquals(listOf(LARGE_UTXO, SMALL_UTXO), state.selectedUtxos)
        assertNull(state.loadError)
        assertFalse(state.isLoading)
        assertTrue(state.isSelectionValid)
    }

    @Test
    fun `loadUtxos retries node not running error`() = test {
        whenever(lightningRepo.listSpendableOutputs()).thenReturn(
            Result.failure(NodeNotRunningError("listSpendableOutputs", NodeLifecycleState.Stopped)),
            Result.success(listOf(LARGE_UTXO)),
        )
        stubFee()

        sut.loadUtxos(REQUIRED_AMOUNT, ADDRESS)
        advanceUntilIdle()

        verify(lightningRepo, times(2)).listSpendableOutputs()
        assertEquals(listOf(LARGE_UTXO), sut.uiState.value.availableUtxos)
        assertNull(sut.uiState.value.loadError)
    }

    @Test
    fun `loadUtxos does not retry node run timeout error`() = test {
        val error = NodeRunTimeoutError("listSpendableOutputs")
        whenever(lightningRepo.listSpendableOutputs()).thenReturn(Result.failure(error))

        sut.loadUtxos(REQUIRED_AMOUNT, ADDRESS)
        advanceUntilIdle()

        verify(lightningRepo, times(1)).listSpendableOutputs()
        assertEquals(error, sut.uiState.value.loadError)
        assertFalse(sut.uiState.value.isLoading)
    }

    @Test
    fun `loadUtxos sets load error after bounded attempts without toast`() = test {
        val error = ServiceError.NodeNotSetup()
        whenever(lightningRepo.listSpendableOutputs()).thenReturn(Result.failure(error))
        val toasts = mutableListOf<Toast>()
        val collectJob = launch { ToastEventBus.events.collect { toasts.add(it) } }

        sut.loadUtxos(REQUIRED_AMOUNT, ADDRESS)
        advanceUntilIdle()

        val state = sut.uiState.value
        verify(lightningRepo, times(3)).listSpendableOutputs()
        assertEquals(error, state.loadError)
        assertFalse(state.isLoading)
        assertTrue(state.availableUtxos.isEmpty())
        assertFalse(state.isSelectionValid)
        assertTrue(toasts.isEmpty())
        collectJob.cancel()
    }

    @Test
    fun `loadUtxos does not retry non transient list failure`() = test {
        whenever(lightningRepo.listSpendableOutputs()).thenReturn(Result.failure(AppError("wallet failure")))

        sut.loadUtxos(REQUIRED_AMOUNT, ADDRESS)
        advanceUntilIdle()

        verify(lightningRepo, times(1)).listSpendableOutputs()
        assertIs<AppError>(sut.uiState.value.loadError)
    }

    @Test
    fun `loadUtxos does not retry fee calculation failure`() = test {
        val error = ServiceError.NodeNotSetup()
        whenever(lightningRepo.listSpendableOutputs()).thenReturn(Result.success(listOf(LARGE_UTXO)))
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.failure(error))

        sut.loadUtxos(REQUIRED_AMOUNT, ADDRESS)
        advanceUntilIdle()

        verify(lightningRepo, times(1)).listSpendableOutputs()
        verify(lightningRepo, times(1)).calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        assertEquals(error, sut.uiState.value.loadError)
        assertFalse(sut.uiState.value.isLoading)
    }

    @Test
    fun `loadUtxos clears load error on successful retry`() = test {
        whenever(lightningRepo.listSpendableOutputs()).thenReturn(Result.failure(AppError("wallet failure")))
        sut.loadUtxos(REQUIRED_AMOUNT, ADDRESS)
        advanceUntilIdle()

        loadUtxos(utxos = listOf(LARGE_UTXO))

        val state = sut.uiState.value
        assertNull(state.loadError)
        assertEquals(listOf(LARGE_UTXO), state.availableUtxos)
    }

    @Test
    fun `loadUtxos keeps load error visible while retry is in progress`() = test {
        val error = AppError("wallet failure")
        whenever(lightningRepo.listSpendableOutputs()).thenReturn(Result.failure(error))
        sut.loadUtxos(REQUIRED_AMOUNT, ADDRESS)
        advanceUntilIdle()

        whenever(lightningRepo.listSpendableOutputs()).thenReturn(Result.failure(ServiceError.NodeNotSetup()))
        sut.loadUtxos(REQUIRED_AMOUNT, ADDRESS)

        val state = sut.uiState.value
        assertTrue(state.isLoading)
        assertEquals(error, state.loadError)
        advanceUntilIdle()
        assertFalse(sut.uiState.value.isLoading)
    }

    @Test
    fun `setOnchainActivities does not reset manual selection`() = test {
        loadUtxos(utxos = listOf(LARGE_UTXO, SMALL_UTXO))
        sut.onToggleUtxo(SMALL_UTXO)

        sut.setOnchainActivities(emptyList())
        advanceUntilIdle()

        assertEquals(listOf(LARGE_UTXO), sut.uiState.value.selectedUtxos)
        verify(lightningRepo, times(1)).listSpendableOutputs()
    }

    private suspend fun TestScope.loadUtxos(
        utxos: List<SpendableUtxo>,
        requiredAmount: ULong = REQUIRED_AMOUNT,
        fee: ULong = FEE,
    ) {
        whenever(lightningRepo.listSpendableOutputs()).thenReturn(Result.success(utxos))
        stubFee(fee)
        sut.loadUtxos(requiredAmount, ADDRESS)
        advanceUntilIdle()
    }

    private suspend fun stubFee(fee: ULong = FEE) {
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(fee))
    }
}
