package to.bitkit.ui.screens.wallets.receive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import to.bitkit.ui.components.Keyboard
import to.bitkit.ui.components.NumberPadTextField
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.clickableAlpha
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
    var keyboardVisible by remember { mutableStateOf(false) }

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
        onTextChanged = { newNote -> noteText = newNote },
        keyboardVisible = keyboardVisible,
        onClickBalance = { keyboardVisible = true },
        onInputChanged = { newText -> input = newText },
        onContinueKeyboard = { keyboardVisible = false },
        onContinueGeneral = {}
    )
}

@Composable
fun EditInvoiceContent(
    input: String,
    noteText: String,
    keyboardVisible: Boolean,
    primaryDisplay: PrimaryDisplay,
    displayUnit: BitcoinDisplayUnit,
    onEvent: (SendEvent) -> Unit,
    onBack: () -> Unit,
    onContinueKeyboard: () -> Unit,
    onClickBalance: () -> Unit,
    onContinueGeneral: () -> Unit,
    onTextChanged: (String) -> Unit,
    onInputChanged: (String) -> Unit,
) {

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
            Spacer(Modifier.height(32.dp))

            NumberPadTextField(
                input = input,
                displayUnit = displayUnit,
                primaryDisplay = primaryDisplay,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableAlpha(onClick = onClickBalance)
                    .testTag("amount_input_field")
            )

            if (keyboardVisible) {
                Spacer(modifier = Modifier.weight(1f))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    UnitButton(modifier = Modifier.height(28.dp))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

                Keyboard(
                    onClick = { number ->
                        onInputChanged(if (input == "0") number else input + number)
                    },
                    onClickBackspace = {
                        onInputChanged(if (input.length > 1) input.dropLast(1) else "0")
                    },
                    isDecimal = primaryDisplay == PrimaryDisplay.FIAT,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("amount_keyboard"),
                )

                Spacer(modifier = Modifier.height(41.dp))

                PrimaryButton(
                    text = stringResource(R.string.continue_button),
                    onClick = onContinueKeyboard,
                )
            } else {

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
                    onClick = onContinueGeneral,
                )
            }

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
            onTextChanged = {},
            keyboardVisible = false,
            onClickBalance = {},
            onInputChanged = {},
            onContinueGeneral = {},
            onContinueKeyboard = {}
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
            onTextChanged = {},
            keyboardVisible = false,
            onClickBalance = {},
            onInputChanged = {},
            onContinueGeneral = {},
            onContinueKeyboard = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview3() {
    AppThemeSurface {
        EditInvoiceContent(
            input = "123",
            noteText = "Note text",
            primaryDisplay = PrimaryDisplay.BITCOIN,
            displayUnit = BitcoinDisplayUnit.MODERN,
            onEvent = {},
            onBack = {},
            onTextChanged = {},
            keyboardVisible = true,
            onClickBalance = {},
            onInputChanged = {},
            onContinueGeneral = {},
            onContinueKeyboard = {}
        )
    }
}

