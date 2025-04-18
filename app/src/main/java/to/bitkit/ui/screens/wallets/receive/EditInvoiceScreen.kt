package to.bitkit.ui.screens.wallets.receive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import okhttp3.internal.toLongOrDefault
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.viewmodels.CurrencyUiState
import to.bitkit.viewmodels.CurrencyViewModel
import java.math.BigDecimal

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


@Composable
private fun AmountInputHandler(
    input: String,
    primaryDisplay: PrimaryDisplay,
    displayUnit: BitcoinDisplayUnit,
    onInputChanged: (String) -> Unit,
    onAmountCalculated: (String) -> Unit,
    currencyVM: CurrencyViewModel
) {
    LaunchedEffect(primaryDisplay) {
        val newInput = when (primaryDisplay) {
            PrimaryDisplay.BITCOIN -> { //Convert fiat to sats
                val amountLong = currencyVM.convertFiatToSats(input.replace(",", "").toDoubleOrNull() ?: 0.0) ?: 0
                if (amountLong > 0.0) amountLong.toString() else ""
            }

            PrimaryDisplay.FIAT -> { //Convert sats to fiat
                val convertedAmount = currencyVM.convert(input.toLongOrDefault(0L))
                if ((convertedAmount?.value ?: BigDecimal(0)) > BigDecimal(0)) convertedAmount?.formatted.toString() else ""
            }
        }
        onInputChanged(newInput)
    }

    LaunchedEffect(input) {
        val sats = when (primaryDisplay) {
            PrimaryDisplay.BITCOIN -> {
                if (displayUnit == BitcoinDisplayUnit.MODERN) input else (input.toLongOrDefault(0L) * 100_000_000).toString()
            }

            PrimaryDisplay.FIAT -> {
                val convertedAmount = currencyVM.convertFiatToSats(input.replace(",", "").toDoubleOrNull() ?: 0.0) ?: 0L
                convertedAmount.toString()
            }
        }
        onAmountCalculated(sats)
    }
}
