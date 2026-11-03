package to.bitkit.viewmodels

import android.content.Context
import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.BalanceUnitSwitch
import to.bitkit.models.PrimaryDisplay
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.CurrencyState
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import kotlin.test.assertEquals

class CurrencyViewModelTest : BaseUnitTest() {
    private companion object {
        const val BITCOIN = "Bitcoin"
        const val USD = "USD"
        const val SWITCHED_TO_BITCOIN = "Switched to Bitcoin"
        const val SWITCHED_TO_USD = "Switched to USD"
        const val SWITCH_BACK_TO_BITCOIN = "Tap your wallet balance to switch it back to Bitcoin."
        const val SWITCH_BACK_TO_USD = "Tap your wallet balance to switch it back to USD."
    }

    private val context = mock<Context>()
    private val currencyRepo = mock<CurrencyRepo>()

    private lateinit var sut: CurrencyViewModel

    @Before
    fun setUp() {
        whenever(currencyRepo.currencyState).thenReturn(MutableStateFlow(CurrencyState()))
        whenever(context.getString(R.string.settings__general__unit_bitcoin)).thenReturn(BITCOIN)
        sut = CurrencyViewModel(
            context = context,
            currencyRepo = currencyRepo,
        )
    }

    @Test
    fun `switchBalanceUnit shows fiat title and bitcoin description`() = test {
        whenever(currencyRepo.switchBalanceUnit()).thenReturn(
            Result.success(
                BalanceUnitSwitch(
                    previousDisplay = PrimaryDisplay.BITCOIN,
                    newDisplay = PrimaryDisplay.FIAT,
                    selectedCurrency = USD,
                ),
            ),
        )
        whenever(context.getString(R.string.wallet__balance_unit_switched_title, USD))
            .thenReturn(SWITCHED_TO_USD)
        whenever(context.getString(R.string.wallet__balance_unit_switched_message, BITCOIN))
            .thenReturn(SWITCH_BACK_TO_BITCOIN)

        ToastEventBus.events.test {
            sut.switchBalanceUnit()

            val toast = awaitItem()
            assertEquals(SWITCHED_TO_USD, toast.title)
            assertEquals(SWITCH_BACK_TO_BITCOIN, toast.description)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switchBalanceUnit shows bitcoin title and fiat description`() = test {
        whenever(currencyRepo.switchBalanceUnit()).thenReturn(
            Result.success(
                BalanceUnitSwitch(
                    previousDisplay = PrimaryDisplay.FIAT,
                    newDisplay = PrimaryDisplay.BITCOIN,
                    selectedCurrency = USD,
                ),
            ),
        )
        whenever(context.getString(R.string.wallet__balance_unit_switched_title, BITCOIN))
            .thenReturn(SWITCHED_TO_BITCOIN)
        whenever(context.getString(R.string.wallet__balance_unit_switched_message, USD))
            .thenReturn(SWITCH_BACK_TO_USD)

        ToastEventBus.events.test {
            sut.switchBalanceUnit()

            val toast = awaitItem()
            assertEquals(SWITCHED_TO_BITCOIN, toast.title)
            assertEquals(SWITCH_BACK_TO_USD, toast.description)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
