package to.bitkit.ui.screens.widgets.calculator.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import to.bitkit.R
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.CLASSIC_DECIMALS
import to.bitkit.models.MoneyType
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.components.NavBarSpacer
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadType
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.screens.widgets.calculator.CALCULATOR_FIAT_DECIMAL_PLACES
import to.bitkit.ui.screens.widgets.calculator.CalculatorViewModel
import to.bitkit.ui.screens.widgets.calculator.applyNumberPadInput
import to.bitkit.ui.screens.widgets.calculator.calculatorDecimalSeparator
import to.bitkit.ui.screens.widgets.calculator.formatBitcoinValue
import to.bitkit.ui.screens.widgets.calculator.formatFiatPlaceholder
import to.bitkit.ui.screens.widgets.calculator.formatFiatValue
import to.bitkit.ui.screens.widgets.components.WidgetCardDimens
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun CalculatorCard(
    modifier: Modifier = Modifier,
    dismissNumberPadKey: Int = 0,
    onInputActiveChange: (Boolean) -> Unit = {},
    calculatorViewModel: CalculatorViewModel = hiltViewModel(),
) {
    val uiState by calculatorViewModel.uiState.collectAsStateWithLifecycle()

    CalculatorCardEditor(
        modifier = modifier,
        btcPrimaryDisplayUnit = uiState.displayUnit,
        btcValue = uiState.btcValue,
        onBtcChange = calculatorViewModel::onBtcInputChanged,
        fiatSymbol = uiState.currencySymbol,
        fiatName = uiState.selectedCurrency,
        fiatValue = uiState.fiatValue,
        onFiatChange = calculatorViewModel::onFiatInputChanged,
        dismissNumberPadKey = dismissNumberPadKey,
        onInputActiveChange = onInputActiveChange,
    )
}

@Composable
fun CalculatorCardEditor(
    modifier: Modifier = Modifier,
    btcPrimaryDisplayUnit: BitcoinDisplayUnit,
    btcValue: String,
    onBtcChange: (String) -> Unit,
    fiatSymbol: String,
    fiatName: String,
    fiatValue: String,
    onFiatChange: (String) -> Unit,
    dismissNumberPadKey: Int = 0,
    onInputActiveChange: (Boolean) -> Unit = {},
) {
    val numpadState = rememberNumpadState()

    Column(modifier = modifier) {
        Content(
            btcPrimaryDisplayUnit = btcPrimaryDisplayUnit,
            btcValue = btcValue,
            fiatSymbol = fiatSymbol,
            fiatName = fiatName,
            fiatValue = fiatValue,
            activeInput = numpadState.activeInput,
            onSelectInput = numpadState::selectInput,
            modifier = Modifier.fillMaxWidth(),
        )

        NumpadHost(
            state = numpadState,
            dismissNumberPadKey = dismissNumberPadKey,
            onInputActiveChange = onInputActiveChange,
            btcValue = btcValue,
            btcPrimaryDisplayUnit = btcPrimaryDisplayUnit,
            fiatValue = fiatValue,
            onBtcChange = onBtcChange,
            onFiatChange = onFiatChange,
        )
    }
}

@Composable
private fun rememberNumpadState(): NumpadState {
    val activeInput = rememberSaveable { mutableStateOf<MoneyType?>(null) }
    val selectedInput = rememberSaveable { mutableStateOf<MoneyType?>(null) }
    val visibilityState = remember {
        MutableTransitionState(activeInput.value != null).apply {
            targetState = activeInput.value != null
        }
    }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    return remember(activeInput, selectedInput, visibilityState, bringIntoViewRequester) {
        NumpadState(
            activeInputState = activeInput,
            selectedInputState = selectedInput,
            visibilityState = visibilityState,
            bringIntoViewRequester = bringIntoViewRequester,
        )
    }
}

@Stable
private class NumpadState(
    activeInputState: MutableState<MoneyType?>,
    selectedInputState: MutableState<MoneyType?>,
    val visibilityState: MutableTransitionState<Boolean>,
    val bringIntoViewRequester: BringIntoViewRequester,
) {
    var activeInput by activeInputState
        private set

    var selectedInput by selectedInputState
        private set

    var errorKey by mutableStateOf<String?>(null)
        private set

    fun selectInput(input: MoneyType) {
        selectedInput = input
        activeInput = input
        visibilityState.targetState = true
        clearError()
    }

    fun dismiss() {
        activeInput = null
        visibilityState.targetState = false
        clearError()
    }

    fun showError(key: String) {
        errorKey = key
    }

    fun clearError() {
        errorKey = null
    }
}

@Composable
private fun ColumnScope.NumpadHost(
    state: NumpadState,
    dismissNumberPadKey: Int,
    onInputActiveChange: (Boolean) -> Unit,
    btcValue: String,
    btcPrimaryDisplayUnit: BitcoinDisplayUnit,
    fiatValue: String,
    onBtcChange: (String) -> Unit,
    onFiatChange: (String) -> Unit,
) {
    NumpadEffects(
        state = state,
        dismissNumberPadKey = dismissNumberPadKey,
        onInputActiveChange = onInputActiveChange,
    )

    Numpad(
        state = state,
        btcValue = btcValue,
        btcPrimaryDisplayUnit = btcPrimaryDisplayUnit,
        fiatValue = fiatValue,
        onBtcChange = onBtcChange,
        onFiatChange = onFiatChange,
    )
}

@Composable
private fun NumpadEffects(
    state: NumpadState,
    dismissNumberPadKey: Int,
    onInputActiveChange: (Boolean) -> Unit,
) {
    val updatedOnInputActiveChange by rememberUpdatedState(onInputActiveChange)
    val isInputTargetActive = state.visibilityState.targetState

    LaunchedEffect(dismissNumberPadKey) { state.dismiss() }

    LaunchedEffect(state.activeInput, state.selectedInput) {
        if (state.activeInput == null || state.selectedInput == null) return@LaunchedEffect
        delay(BRING_NUMBER_PAD_INTO_VIEW_DELAY)
        state.bringIntoViewRequester.bringIntoView()
    }

    LaunchedEffect(isInputTargetActive) {
        updatedOnInputActiveChange(isInputTargetActive)
    }

    LaunchedEffect(state.errorKey) {
        if (state.errorKey == null) return@LaunchedEffect
        delay(ERROR_DELAY)
        state.clearError()
    }

    DisposableEffect(Unit) {
        onDispose { updatedOnInputActiveChange(false) }
    }
}

@Composable
private fun ColumnScope.Numpad(
    state: NumpadState,
    btcValue: String,
    btcPrimaryDisplayUnit: BitcoinDisplayUnit,
    fiatValue: String,
    onBtcChange: (String) -> Unit,
    onFiatChange: (String) -> Unit,
) {
    val selectedInput = state.selectedInput

    AnimatedVisibility(
        visibleState = state.visibilityState,
        enter = EnterTransition.None,
        exit = fadeOut() + shrinkVertically(),
    ) {
        selectedInput?.let { input ->
            Column(
                modifier = Modifier.bringIntoViewRequester(state.bringIntoViewRequester)
            ) {
                VerticalSpacer(8.dp)
                NumberPad(
                    onPress = { key ->
                        val currentValue = currentInputValue(
                            input = input,
                            btcValue = btcValue,
                            fiatValue = fiatValue,
                        )
                        val nextValue = nextInputValue(
                            input = input,
                            key = key,
                            btcValue = btcValue,
                            btcPrimaryDisplayUnit = btcPrimaryDisplayUnit,
                            fiatValue = fiatValue,
                        )

                        if (nextValue == currentValue && key != KEY_DELETE) {
                            state.showError(key)
                            return@NumberPad
                        }
                        state.clearError()

                        when (input) {
                            MoneyType.BITCOIN -> onBtcChange(nextValue)
                            MoneyType.FIAT -> onFiatChange(nextValue)
                        }
                    },
                    type = when (input) {
                        MoneyType.BITCOIN if btcPrimaryDisplayUnit.isModern() -> NumberPadType.INTEGER
                        else -> NumberPadType.DECIMAL
                    },
                    decimalSeparator = calculatorDecimalSeparator(),
                    errorKey = state.errorKey,
                    includeNavigationBarsPadding = true,
                    modifier = Modifier
                        .testTag("CalculatorNumberPad")
                )
                NavBarSpacer(modifier = Modifier.background(MaterialTheme.colorScheme.background))
            }
        }
    }
}

private fun currentInputValue(
    input: MoneyType,
    btcValue: String,
    fiatValue: String,
): String = when (input) {
    MoneyType.BITCOIN -> btcValue
    MoneyType.FIAT -> fiatValue
}

private fun nextInputValue(
    input: MoneyType,
    key: String,
    btcValue: String,
    btcPrimaryDisplayUnit: BitcoinDisplayUnit,
    fiatValue: String,
): String = when (input) {
    MoneyType.BITCOIN -> applyNumberPadInput(
        rawValue = btcValue,
        key = key,
        maxDecimalPlaces = CLASSIC_DECIMALS.takeUnless {
            btcPrimaryDisplayUnit.isModern()
        },
    )

    MoneyType.FIAT -> applyNumberPadInput(
        rawValue = fiatValue,
        key = key,
        maxDecimalPlaces = CALCULATOR_FIAT_DECIMAL_PLACES,
    )
}

@Composable
private fun Content(
    modifier: Modifier = Modifier,
    btcPrimaryDisplayUnit: BitcoinDisplayUnit,
    btcValue: String,
    fiatSymbol: String,
    fiatName: String,
    fiatValue: String,
    activeInput: MoneyType? = null,
    onSelectInput: (MoneyType) -> Unit = {},
) {
    Box(
        modifier = modifier
            .clip(shape = MaterialTheme.shapes.medium)
            .background(Colors.Gray6)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            CalculatorInput(
                value = formatBitcoinValue(btcValue, btcPrimaryDisplayUnit),
                currencySymbol = BITCOIN_SYMBOL,
                currencyName = stringResource(R.string.settings__general__unit_bitcoin),
                isActive = activeInput == MoneyType.BITCOIN,
                onClick = { onSelectInput(MoneyType.BITCOIN) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("CalculatorBtcInput")
            )

            VerticalSpacer(16.dp)

            CalculatorInput(
                value = formatFiatValue(fiatValue),
                currencySymbol = fiatSymbol,
                currencyName = fiatName,
                isActive = activeInput == MoneyType.FIAT,
                onClick = { onSelectInput(MoneyType.FIAT) },
                placeholder = formatFiatPlaceholder(fiatValue),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("CalculatorFiatInput")
            )
        }
    }
}

private val BRING_NUMBER_PAD_INTO_VIEW_DELAY = 120.milliseconds
private val ERROR_DELAY = 500.milliseconds

@Composable
fun CalculatorCardSmall(
    btcPrimaryDisplayUnit: BitcoinDisplayUnit,
    btcValue: String,
    fiatSymbol: String,
    fiatValue: String,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        modifier = modifier
            .size(WidgetCardDimens.COMPACT_CARD_SIZE)
            .clip(shape = MaterialTheme.shapes.medium)
            .background(Colors.Gray6)
            .padding(16.dp)
            .testTag("calculator_card_small")
    ) {
        ReadOnlyRow(
            currencySymbol = BITCOIN_SYMBOL,
            value = formatBitcoinValue(btcValue, btcPrimaryDisplayUnit),
            iconSize = 24.dp,
            rowPadding = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("CalculatorSmallBtcRow")
        )
        ReadOnlyRow(
            currencySymbol = fiatSymbol,
            value = formatFiatValue(fiatValue),
            iconSize = 24.dp,
            rowPadding = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("CalculatorSmallFiatRow")
        )
    }
}

@Composable
private fun ReadOnlyRow(
    currencySymbol: String,
    value: String,
    iconSize: Dp,
    rowPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val displayCurrencySymbol = currencySymbol.toCalculatorDisplaySymbol()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(Colors.Black)
            .padding(rowPadding)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .background(color = Colors.Gray6, shape = CircleShape)
                .size(iconSize)
        ) {
            BodyMSB(
                text = displayCurrencySymbol,
                color = Colors.Brand,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        BodyMSB(
            text = value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(16.dp)
        ) {
            CalculatorCardEditor(
                btcValue = "1800000000",
                onBtcChange = {},
                fiatSymbol = "$",
                fiatValue = "4.55",
                fiatName = "USD",
                onFiatChange = {},
                btcPrimaryDisplayUnit = BitcoinDisplayUnit.MODERN,
                modifier = Modifier.fillMaxWidth()
            )

            CalculatorCardEditor(
                btcValue = "22200000",
                onBtcChange = {},
                fiatSymbol = "$",
                fiatValue = "4.55",
                fiatName = "USD",
                onFiatChange = {},
                btcPrimaryDisplayUnit = BitcoinDisplayUnit.CLASSIC,
                modifier = Modifier.fillMaxWidth()
            )

            CalculatorCardSmall(
                btcValue = "10000",
                fiatValue = "6.25",
                fiatSymbol = "$",
                btcPrimaryDisplayUnit = BitcoinDisplayUnit.MODERN,
            )
        }
    }
}
