package to.bitkit.ui.screens.wallets.usdt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.UsdtException
import to.bitkit.R
import to.bitkit.ext.nowMillis
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAmountError
import to.bitkit.models.PaykitAsset
import to.bitkit.models.PaykitExchangeRate
import to.bitkit.repositories.paykitRate
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadActionButton
import to.bitkit.ui.components.NumberPadAmountText
import to.bitkit.ui.components.NumberPadType
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.UsdtAmountHeader
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.NumberPadInputHandler

@Composable
internal fun ColumnScope.UsdtReceiveAmountContent(
    amount: String,
    allowEmpty: Boolean,
    busy: Boolean,
    error: Int?,
    limits: UsdtException.DepositAmountOutOfRange?,
    onAmount: (String) -> Unit,
    onContinue: () -> Unit,
) {
    val currencies = LocalCurrencies.current
    var bitcoinInput by remember { mutableStateOf<String?>(null) }
    var conversionError by remember { mutableStateOf<Int?>(null) }
    val isSatsInput = bitcoinInput != null && currencies.displayUnit.isModern()
    fun updateInput(value: String) {
        conversionError = null
        if (bitcoinInput == null) {
            onAmount(value)
            return
        }
        bitcoinInput = value
        if (value.isEmpty()) {
            onAmount("")
            return
        }
        convertBitcoinInput(value, isSatsInput, currencies.paykitRate, onAmount) { conversionError = it }
    }
    Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.heightIn(min = 156.dp)) {
        ReceiveAmountHeader(amount, bitcoinInput)
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
        ) {
            val message = conversionError?.let { stringResource(it) } ?: depositAmountMessage(amount, limits, error)
            if (message != null) BodyS(message, color = Colors.Brand, modifier = Modifier.weight(1f)) else FillWidth()
            ReceiveUnitButton(
                amount = amount,
                bitcoinInput = bitcoinInput,
                busy = busy,
                onInput = { bitcoinInput = it },
                onError = { conversionError = it },
            )
        }
    }
    HorizontalDivider()
    VerticalSpacer(24.dp)
    ReceiveAmountKeypad(bitcoinInput, amount, isSatsInput, busy, ::updateInput, modifier = Modifier.weight(1f))
    VerticalSpacer(24.dp)
    val validAmount = amount.isEmpty() && allowEmpty || runCatching {
        PaykitAmount.parse(PaykitAsset.USDT, amount)
    }.isSuccess
    PrimaryButton(
        stringResource(R.string.common__continue),
        onClick = onContinue,
        enabled = !busy && conversionError == null && validAmount,
        isLoading = busy,
        modifier = Modifier.testTag("UsdtDepositCreate")
    )
}

@Composable
private fun depositAmountMessage(amount: String, limits: UsdtException.DepositAmountOutOfRange?, error: Int?): String? {
    val value = amount.toBigDecimalOrNull()
    val minText = limits?.minUsdCents
    val min = minText?.toBigDecimalOrNull()?.movePointLeft(2)
    val maxText = limits?.maxUsdCents
    val max = maxText?.toBigDecimalOrNull()?.movePointLeft(2)
    return when {
        value != null && minText != null && min != null && value < min -> stringResource(
            R.string.usdt__deposit_minimum,
            minText.usdCents()
        )
        value != null && maxText != null && max != null && value > max -> stringResource(
            R.string.usdt__deposit_maximum,
            maxText.usdCents()
        )
        error != null -> stringResource(error)
        else -> null
    }
}

private fun bitcoinAmount(value: String, isSatsInput: Boolean): PaykitAmount {
    if (!isSatsInput) return PaykitAmount.parse(PaykitAsset.BTC, value)
    val sats = value.toULongOrNull()?.takeIf { it > 0uL } ?: throw PaykitAmountError.InvalidAmount
    return PaykitAmount(PaykitAsset.BTC, sats)
}

@Composable
private fun ReceiveUnitButton(
    amount: String,
    bitcoinInput: String?,
    busy: Boolean,
    onInput: (String?) -> Unit,
    onError: (Int?) -> Unit,
) {
    val currencies = LocalCurrencies.current
    NumberPadActionButton(
        text = if (bitcoinInput == null) "USD" else "BTC",
        icon = R.drawable.ic_transfer,
        enabled = !busy,
        color = Colors.Usdt,
        modifier = Modifier.testTag("ReceiveNumberPadUnit"),
        onClick = {
            onError(null)
            if (bitcoinInput != null) {
                onInput(null)
            } else if (amount.isEmpty() || amount == "0") {
                onInput("")
            } else {
                try {
                    val bitcoin = PaykitAmount.parse(PaykitAsset.USDT, amount)
                        .convertedTo(PaykitAsset.BTC, currencies.paykitRate, nowMillis())
                    // Switching display units preserves the exact USDT amount until the user edits it.
                    onInput(if (currencies.displayUnit.isModern()) bitcoin.atomic.toString() else bitcoin.value)
                } catch (_: PaykitAmountError.RateUnavailable) {
                    onError(R.string.wallet__payment_request_rate_unavailable)
                } catch (_: PaykitAmountError) {
                    onError(R.string.usdt__error_amount)
                }
            }
        },
    )
}

private fun convertBitcoinInput(
    value: String,
    isSatsInput: Boolean,
    rate: PaykitExchangeRate?,
    onAmount: (String) -> Unit,
    onError: (Int) -> Unit,
) {
    try {
        onAmount(
            bitcoinAmount(value, isSatsInput).convertedTo(PaykitAsset.USDT, rate, nowMillis()).value
        )
    } catch (_: PaykitAmountError.RateUnavailable) {
        onError(R.string.wallet__payment_request_rate_unavailable)
    } catch (_: PaykitAmountError) {
        onError(R.string.usdt__error_amount)
    }
}

@Composable
private fun ReceiveAmountHeader(amount: String, bitcoinInput: String?) {
    if (bitcoinInput == null) {
        UsdtAmountHeader(amount.ifEmpty { "0" }, "Arbitrum One")
    } else {
        Column {
            Caption13Up("$ " + amount.ifEmpty { "0" }, color = Colors.White64)
            VerticalSpacer(16.dp)
            NumberPadAmountText(bitcoinInput.ifEmpty { "0" }, "₿")
        }
    }
}

@Composable
private fun ReceiveAmountKeypad(
    bitcoinInput: String?,
    amount: String,
    isSatsInput: Boolean,
    busy: Boolean,
    onInput: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val decimals = when {
        isSatsInput -> 0
        bitcoinInput == null -> PaykitAsset.USDT.decimals
        else -> PaykitAsset.BTC.decimals
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        NumberPad(
            type = if (isSatsInput) NumberPadType.INTEGER else NumberPadType.DECIMAL,
            availableHeight = maxHeight,
            enabled = !busy,
            onDeleteLongPress = { onInput("") },
            onPress = {
                onInput(
                    NumberPadInputHandler.handleInput(
                        it,
                        bitcoinInput ?: amount,
                        maxLength = 21,
                        maxDecimals = decimals,
                    )
                )
            },
        )
    }
}
