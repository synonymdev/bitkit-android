package to.bitkit.ui.screens.wallets.receive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.AmountInputHandler
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.NumberPadTextField
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.CurrencyUiState
import to.bitkit.viewmodels.SendEvent

@Composable
fun EditInvoiceScreen(
    currencyUiState: CurrencyUiState = LocalCurrencies.current,
    onEvent: (SendEvent) -> Unit,
    onBack: () -> Unit,
) {
    val currencyVM = currencyViewModel ?: return
    var input: String by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }

    AmountInputHandler(
        input = input,
        primaryDisplay = currencyUiState.primaryDisplay,
        displayUnit = currencyUiState.displayUnit,
        onInputChanged = { newInput -> input = newInput },
        onAmountCalculated = { sats -> },
        currencyVM = currencyVM
    )

    EditInvoiceContent(
        input = input,
        noteText = noteText,
        primaryDisplay = currencyUiState.primaryDisplay,
        displayUnit = currencyUiState.displayUnit,
        onEvent = onEvent,
        onBack = onBack,
        onTextChanged = { newNote -> noteText = newNote }
    )
}

@Composable
fun EditInvoiceContent(
    input: String,
    noteText: String,
    primaryDisplay: PrimaryDisplay,
    displayUnit: BitcoinDisplayUnit,
    onEvent: (SendEvent) -> Unit,
    onBack: () -> Unit,
    onTextChanged: (String) -> Unit,
) {
    var keyboardVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
    ) {
        SheetTopBar(stringResource(R.string.wallet__receive_specify)) {
            onEvent(SendEvent.AmountReset)
            onBack()
        }

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            NumberPadTextField(
                input = input,
                displayUnit = displayUnit,
                primaryDisplay = primaryDisplay,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("amount_input_field")
            )
            Spacer(modifier = Modifier.height(44.dp))

            Caption13Up(text = stringResource(R.string.wallet__note), color = Colors.White64)

            Spacer(modifier = Modifier.height(16.dp))

            TextField(
                placeholder = {
                    BodySSB(
                        text = stringResource(R.string.wallet__receive_note_placeholder),
                        color = Colors.White64
                    )
                },
                value = noteText,
                onValueChange = onTextChanged,
                minLines = 4,
                colors = AppTextFieldDefaults.noIndicatorColors,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 74.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Spacer(modifier = Modifier.weight(1f))

            PrimaryButton(
                text = stringResource(R.string.continue_button),
                onClick = { }, //TODO IMPLEMENT
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        EditInvoiceContent(
            input = "123",
            noteText = "",
            primaryDisplay = PrimaryDisplay.BITCOIN,
            displayUnit = BitcoinDisplayUnit.MODERN,
            onEvent = {},
            onBack = {},
            onTextChanged = {}
        )
    }
}


@Preview(showBackground = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        EditInvoiceContent(
            input = "123",
            noteText = "Note text",
            primaryDisplay = PrimaryDisplay.BITCOIN,
            displayUnit = BitcoinDisplayUnit.MODERN,
            onEvent = {},
            onBack = {},
            onTextChanged = {}
        )
    }
}

