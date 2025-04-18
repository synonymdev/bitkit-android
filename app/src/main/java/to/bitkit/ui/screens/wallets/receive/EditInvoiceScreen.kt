package to.bitkit.ui.screens.wallets.receive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.AmountInputHandler
import to.bitkit.ui.currencyViewModel
import to.bitkit.viewmodels.CurrencyUiState

@Composable
fun EditInvoiceScreen(
    currencyUiState: CurrencyUiState = LocalCurrencies.current,
) {
    val currencyVM = currencyViewModel ?: return
    var input: String by remember { mutableStateOf("") }

    AmountInputHandler(
        input = input,
        primaryDisplay = currencyUiState.primaryDisplay,
        displayUnit = currencyUiState.displayUnit,
        onInputChanged = { newInput -> input = newInput },
        onAmountCalculated = { sats ->  },
        currencyVM = currencyVM
    )
}
