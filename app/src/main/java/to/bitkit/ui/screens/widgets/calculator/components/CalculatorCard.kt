package to.bitkit.ui.screens.widgets.calculator.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.CLASSIC_DECIMALS
import to.bitkit.models.MoneyType
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.screens.widgets.calculator.CALCULATOR_FIAT_DECIMAL_PLACES
import to.bitkit.ui.screens.widgets.calculator.applyNumberPadInput
import to.bitkit.ui.screens.widgets.calculator.formatBitcoinPlaceholder
import to.bitkit.ui.screens.widgets.calculator.formatBitcoinValue
import to.bitkit.ui.screens.widgets.calculator.formatFiatPlaceholder
import to.bitkit.ui.screens.widgets.calculator.formatFiatValue
import to.bitkit.ui.screens.widgets.calculator.isBtcValueInSatsRange
import to.bitkit.ui.screens.widgets.components.WidgetCardDimens
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

internal fun currentInputValue(
    input: MoneyType,
    btcValue: String,
    fiatValue: String,
): String = when (input) {
    MoneyType.BITCOIN -> btcValue
    MoneyType.FIAT -> fiatValue
}

internal fun nextInputValue(
    input: MoneyType,
    key: String,
    btcValue: String,
    btcPrimaryDisplayUnit: BitcoinDisplayUnit,
    fiatValue: String,
): String = when (input) {
    MoneyType.BITCOIN -> {
        val nextValue = applyNumberPadInput(
            rawValue = btcValue,
            key = key,
            maxDecimalPlaces = CLASSIC_DECIMALS.takeUnless {
                btcPrimaryDisplayUnit.isModern()
            },
        )
        if (isBtcValueInSatsRange(nextValue, btcPrimaryDisplayUnit)) {
            nextValue
        } else {
            btcValue
        }
    }

    MoneyType.FIAT -> applyNumberPadInput(
        rawValue = fiatValue,
        key = key,
        maxDecimalPlaces = CALCULATOR_FIAT_DECIMAL_PLACES,
    )
}

@Composable
fun CalculatorCard(
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
                placeholder = formatBitcoinPlaceholder(btcValue, btcPrimaryDisplayUnit),
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

@Composable
fun CalculatorCardSmall(
    btcPrimaryDisplayUnit: BitcoinDisplayUnit,
    btcValue: String,
    fiatSymbol: String,
    fiatValue: String,
    modifier: Modifier = Modifier,
    activeInput: MoneyType? = null,
    onSelectInput: ((MoneyType) -> Unit)? = null,
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
            isActive = activeInput == MoneyType.BITCOIN,
            onClick = onSelectInput?.let { { it(MoneyType.BITCOIN) } },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("CalculatorSmallBtcRow")
        )
        ReadOnlyRow(
            currencySymbol = fiatSymbol,
            value = formatFiatValue(fiatValue),
            iconSize = 24.dp,
            rowPadding = 12.dp,
            isActive = activeInput == MoneyType.FIAT,
            onClick = onSelectInput?.let { { it(MoneyType.FIAT) } },
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
    isActive: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val displayCurrencySymbol = currencySymbol.toCalculatorDisplaySymbol()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .clip(inputShape)
            .background(Colors.Black)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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
        InputValue(
            value = value,
            placeholder = "",
            isActive = isActive,
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
            CalculatorCard(
                btcValue = "1800000000",
                fiatSymbol = "$",
                fiatValue = "4.55",
                fiatName = "USD",
                btcPrimaryDisplayUnit = BitcoinDisplayUnit.MODERN,
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
