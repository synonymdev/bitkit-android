package to.bitkit.ui.screens.wallets.usdt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synonym.bitkitcore.UsdtException
import to.bitkit.R
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadType
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.UsdtAmountHeader
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.NumberPadInputHandler
import java.text.DecimalFormatSymbols

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
    Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.heightIn(min = 146.dp)) {
        UsdtAmountHeader(amount.ifEmpty { "0" })
        depositAmountMessage(amount, limits, error)?.let { message ->
            BodyS(message, color = Colors.Brand, letterSpacing = 0.sp, modifier = Modifier.padding(vertical = 16.dp))
        }
    }
    HorizontalDivider()
    VerticalSpacer(34.dp)
    BoxWithConstraints(contentAlignment = Alignment.BottomCenter, modifier = Modifier.weight(1f).fillMaxWidth()) {
        NumberPad(
            type = NumberPadType.DECIMAL,
            decimalSeparator = DecimalFormatSymbols.getInstance().decimalSeparator.toString(),
            availableHeight = maxHeight,
            enabled = !busy,
            onDeleteLongPress = { onAmount("") },
            onPress = {
                onAmount(
                    NumberPadInputHandler.handleInput(
                        it,
                        amount,
                        maxLength = 21,
                        maxDecimals = PaykitAsset.USDT.decimals
                    )
                )
            },
        )
    }
    VerticalSpacer(12.dp)
    val validAmount = amount.isEmpty() && allowEmpty || runCatching {
        PaykitAmount.parse(PaykitAsset.USDT, amount)
    }.isSuccess
    PrimaryButton(
        stringResource(R.string.common__continue),
        onClick = onContinue,
        enabled = !busy && validAmount,
        isLoading = busy,
        textStyle = UsdtDetailStyles.Body,
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
