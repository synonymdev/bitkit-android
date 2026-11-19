package to.bitkit.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.models.PrimaryDisplay
import to.bitkit.repositories.CurrencyState
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.LocalCurrencyViewModel
import to.bitkit.viewmodels.CurrencyViewModel
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoneyTextTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val currencyViewModel = mock<CurrencyViewModel>()

    @Test
    fun `bitcoin amounts show while the selected currency has no rate`() {
        whenever(currencyViewModel.convert(any(), anyOrNull())).thenReturn(null)
        var bitcoin: String? = "unset"
        var fiat: String? = "unset"

        composeRule.setContent {
            CompositionLocalProvider(LocalCurrencyViewModel provides currencyViewModel) {
                val currencies = CurrencyState(primaryDisplay = PrimaryDisplay.BITCOIN)
                bitcoin = rememberMoneyText(1_234L, currencies = currencies, showSymbol = false)
                fiat = rememberMoneyText(1_234L, currencies = currencies, unit = PrimaryDisplay.FIAT)
            }
        }
        composeRule.waitForIdle()

        assertEquals("1 234", bitcoin?.replace(' ', ' '))
        assertNull(fiat, "a fiat amount showed without a rate")
    }

    @Test
    fun `bitcoin captions show while the selected currency has no rate`() {
        whenever(currencyViewModel.convert(any(), anyOrNull())).thenReturn(null)

        composeRule.setContent {
            CompositionLocalProvider(
                LocalCurrencyViewModel provides currencyViewModel,
                LocalCurrencies provides CurrencyState(primaryDisplay = PrimaryDisplay.FIAT),
            ) {
                MoneyCaptionB(sats = 1_234L)
            }
        }

        composeRule.onNodeWithTag("MoneyText").assertIsDisplayed()
    }
}
