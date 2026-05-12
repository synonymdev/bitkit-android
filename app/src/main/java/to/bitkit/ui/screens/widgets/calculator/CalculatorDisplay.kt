package to.bitkit.ui.screens.widgets.calculator

import to.bitkit.ext.removeSpaces
import to.bitkit.ext.toLongOrDefault
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.CLASSIC_DECIMALS
import to.bitkit.models.DECIMAL_SEPARATOR
import to.bitkit.models.FIAT_GROUPING_SEPARATOR
import to.bitkit.models.SATS_GROUPING_SEPARATOR
import to.bitkit.models.SATS_IN_BTC
import to.bitkit.models.asBtc
import to.bitkit.models.formatCurrency
import to.bitkit.models.widget.CalculatorValues
import to.bitkit.ui.components.KEY_000
import to.bitkit.ui.components.KEY_DECIMAL
import to.bitkit.ui.components.KEY_DELETE
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale

internal fun calculatorBtcValueToSats(
    btcValue: String,
    displayUnit: BitcoinDisplayUnit,
): Long {
    val satsOrBtc = btcValue.removeSpaces()
    return when (displayUnit) {
        BitcoinDisplayUnit.MODERN -> satsOrBtc.toLongOrDefault()
        BitcoinDisplayUnit.CLASSIC -> {
            val btcDecimal = satsOrBtc.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val satsDecimal = btcDecimal.multiply(BigDecimal(SATS_IN_BTC))
            val roundedNumber = satsDecimal.setScale(0, RoundingMode.HALF_UP)
            roundedNumber.toLong()
        }
    }
}

internal fun convertCalculatorBtcValueUnit(
    btcValue: String,
    fromDisplayUnit: BitcoinDisplayUnit,
    toDisplayUnit: BitcoinDisplayUnit,
): String {
    if (btcValue.isEmpty() || fromDisplayUnit == toDisplayUnit) return btcValue

    val sats = calculatorBtcValueToSats(btcValue, fromDisplayUnit)
    return calculatorSatsToBtcValue(sats, toDisplayUnit)
}

internal fun calculatorSatsToBtcValue(
    sats: Long,
    displayUnit: BitcoinDisplayUnit,
): String {
    return when (displayUnit) {
        BitcoinDisplayUnit.MODERN -> sats.toString()
        BitcoinDisplayUnit.CLASSIC -> sats.asBtc()
            .formatCurrency(decimalPlaces = CLASSIC_DECIMALS)
            .orEmpty()
    }
}

internal fun CalculatorValues.resolveCalculatorSatsValue(): Long {
    satsValue?.let { return it }
    if (btcValue.isEmpty()) return 0L
    return calculatorBtcValueToSats(
        btcValue = btcValue,
        displayUnit = displayUnit ?: inferLegacyCalculatorDisplayUnit(btcValue),
    )
}

private fun inferLegacyCalculatorDisplayUnit(btcValue: String): BitcoinDisplayUnit {
    val normalizedValue = btcValue.removeSpaces()
    return if (normalizedValue.any { it == '.' || it == ',' }) {
        BitcoinDisplayUnit.CLASSIC
    } else {
        BitcoinDisplayUnit.MODERN
    }
}

internal fun formatBitcoinValue(
    btcValue: String,
    displayUnit: BitcoinDisplayUnit,
    locale: Locale = Locale.getDefault(),
): String {
    if (btcValue.isEmpty()) return ""
    return when (displayUnit) {
        BitcoinDisplayUnit.MODERN -> formatGroupedInteger(
            value = btcValue.filter { it.isDigit() },
            groupingSeparator = SATS_GROUPING_SEPARATOR,
        )

        BitcoinDisplayUnit.CLASSIC -> formatGroupedDecimal(
            value = sanitizeDecimalInput(btcValue, locale),
            groupingSeparator = SATS_GROUPING_SEPARATOR,
            decimalSeparator = DECIMAL_SEPARATOR,
        )
    }
}

internal fun formatFiatValue(
    fiatValue: String,
    locale: Locale = Locale.getDefault(),
): String {
    if (fiatValue.isEmpty()) return ""
    val normalizedFiatValue = sanitizeDecimalInput(
        raw = normalizeCalculatorDecimalInput(
            rawValue = fiatValue,
            locale = locale,
            maxDecimalPlaces = CALCULATOR_FIAT_DECIMAL_PLACES,
        ),
        maxDecimalPlaces = CALCULATOR_FIAT_DECIMAL_PLACES,
    )
    return formatGroupedDecimal(
        value = normalizedFiatValue,
        groupingSeparator = FIAT_GROUPING_SEPARATOR,
        decimalSeparator = DECIMAL_SEPARATOR,
    )
}

internal fun formatFiatPlaceholder(
    fiatValue: String,
    locale: Locale = Locale.getDefault(),
): String {
    if (fiatValue.isEmpty()) return ""
    val normalizedFiatValue = sanitizeDecimalInput(
        raw = normalizeCalculatorDecimalInput(
            rawValue = fiatValue,
            locale = locale,
            maxDecimalPlaces = CALCULATOR_FIAT_DECIMAL_PLACES,
        ),
        maxDecimalPlaces = CALCULATOR_FIAT_DECIMAL_PLACES,
    )
    if (!normalizedFiatValue.contains(PERIOD_SEPARATOR)) return ""

    val decimalLength = normalizedFiatValue.substringAfter(PERIOD_SEPARATOR).length
    val remainingDecimals = CALCULATOR_FIAT_DECIMAL_PLACES - decimalLength
    return if (remainingDecimals > 0) "0".repeat(remainingDecimals) else ""
}

internal fun applyNumberPadInput(
    rawValue: String,
    key: String,
    maxDecimalPlaces: Int?,
    locale: Locale = Locale.getDefault(),
): String {
    val normalizedRawValue = if (maxDecimalPlaces == null) {
        rawValue
    } else {
        normalizeCalculatorDecimalInput(
            rawValue = rawValue,
            locale = locale,
            maxDecimalPlaces = maxDecimalPlaces,
        )
    }
    val nextValue = when {
        key == KEY_DELETE -> normalizedRawValue.dropLast(1)
        key == KEY_DECIMAL -> appendDecimalSeparator(normalizedRawValue, maxDecimalPlaces)
        key == KEY_000 -> appendCalculatorDigits(normalizedRawValue, KEY_000)
        key.length == 1 && key.first().isDigit() -> appendCalculatorDigits(normalizedRawValue, key)
        else -> normalizedRawValue
    }

    return if (maxDecimalPlaces == null) {
        sanitizeIntegerInput(nextValue)
    } else {
        sanitizeDecimalInput(
            raw = nextValue,
            locale = locale,
            maxDecimalPlaces = maxDecimalPlaces,
        )
    }
}

private fun normalizeCalculatorDecimalInput(
    rawValue: String,
    locale: Locale,
    maxDecimalPlaces: Int?,
): String {
    val value = rawValue.removeSpaces()
    val hasComma = value.contains(COMMA_SEPARATOR)
    val hasPeriod = value.contains(PERIOD_SEPARATOR)
    if (hasComma && hasPeriod) return normalizeMixedDecimalSeparators(value)
    if (!hasComma) return value
    return if (shouldTreatCommaAsGrouping(value, locale, maxDecimalPlaces)) {
        value.replace(oldValue = COMMA_SEPARATOR.toString(), newValue = "")
    } else {
        value.replace(oldChar = COMMA_SEPARATOR, newChar = PERIOD_SEPARATOR)
    }
}

private fun normalizeMixedDecimalSeparators(value: String): String {
    val decimalSeparator = if (value.lastIndexOf(COMMA_SEPARATOR) > value.lastIndexOf(PERIOD_SEPARATOR)) {
        COMMA_SEPARATOR
    } else {
        PERIOD_SEPARATOR
    }
    val groupingSeparator = if (decimalSeparator == COMMA_SEPARATOR) PERIOD_SEPARATOR else COMMA_SEPARATOR
    return value
        .replace(oldValue = groupingSeparator.toString(), newValue = "")
        .replace(oldChar = decimalSeparator, newChar = PERIOD_SEPARATOR)
}

private fun shouldTreatCommaAsGrouping(
    value: String,
    locale: Locale,
    maxDecimalPlaces: Int?,
): Boolean {
    if (value.count { it == COMMA_SEPARATOR } > 1) return true

    val decimalSeparator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    if (decimalSeparator != COMMA_SEPARATOR) return true

    val fractionLength = value.substringAfter(COMMA_SEPARATOR).length
    return maxDecimalPlaces != null && fractionLength > maxDecimalPlaces
}

private fun formatGroupedInteger(
    value: String,
    groupingSeparator: Char,
): String {
    if (value.isEmpty()) return ""
    val normalized = value.trimStart('0').ifEmpty { "0" }
    return normalized.reversed().chunked(GROUP_SIZE).joinToString(groupingSeparator.toString()).reversed()
}

private fun formatGroupedDecimal(
    value: String,
    groupingSeparator: Char,
    decimalSeparator: Char,
): String {
    if (value.isEmpty()) return ""
    if (value == ".") return decimalSeparator.toString()

    val decimalIndex = value.indexOf('.')
    if (decimalIndex == -1) {
        return formatGroupedIntegerPreservingZeros(
            value = value,
            groupingSeparator = groupingSeparator,
        )
    }

    val integerPart = value.substring(0, decimalIndex)
    val decimalPart = value.substring(decimalIndex + 1)
    return formatGroupedIntegerPreservingZeros(
        value = integerPart,
        groupingSeparator = groupingSeparator,
    ) + decimalSeparator + decimalPart
}

private fun appendDecimalSeparator(
    rawValue: String,
    maxDecimalPlaces: Int?,
): String {
    if (maxDecimalPlaces == null || rawValue.contains('.')) return rawValue
    return if (rawValue.isEmpty()) "0." else "$rawValue."
}

private fun appendCalculatorDigits(rawValue: String, digits: String): String {
    if (rawValue != "0") return rawValue + digits
    return digits.trimStart('0').ifEmpty { "0" }
}

internal fun calculatorDecimalSeparator(): String = DECIMAL_SEPARATOR.toString()

private fun formatGroupedIntegerPreservingZeros(
    value: String,
    groupingSeparator: Char,
): String {
    if (value.isEmpty()) return ""
    return value.reversed().chunked(GROUP_SIZE).joinToString(groupingSeparator.toString()).reversed()
}

private const val GROUP_SIZE = 3
private const val COMMA_SEPARATOR = ','
private const val PERIOD_SEPARATOR = '.'
