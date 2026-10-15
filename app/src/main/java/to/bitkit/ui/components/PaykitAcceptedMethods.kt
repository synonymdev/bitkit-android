package to.bitkit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.collections.immutable.ImmutableList
import to.bitkit.R
import to.bitkit.models.PaykitAsset
import to.bitkit.repositories.MethodId
import to.bitkit.ui.theme.AppTextStyles
import to.bitkit.ui.theme.Colors

@Composable
fun PaykitAcceptedMethods(
    title: String,
    availableMethods: ImmutableList<String>,
    selectedMethods: ImmutableList<String>,
    testTagPrefix: String,
    onClickMethod: (PaykitPaymentOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Caption13Up(text = title, color = Colors.White64)
        VerticalSpacer(8.dp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaykitPaymentOption.entries.forEach { option ->
                val endpoints = option.endpoints(availableMethods)
                if (endpoints.isNotEmpty()) {
                    val selected = endpoints.any { it in selectedMethods }
                    NumberPadActionButton(
                        text = when (option) {
                            PaykitPaymentOption.Savings -> stringResource(R.string.lightning__savings)
                            PaykitPaymentOption.Spending -> stringResource(R.string.lightning__spending)
                            PaykitPaymentOption.Usdt -> "USDT"
                        },
                        color = when (option) {
                            PaykitPaymentOption.Savings -> Colors.Brand
                            PaykitPaymentOption.Spending -> Colors.Purple
                            PaykitPaymentOption.Usdt -> Colors.Usdt
                        },
                        outlined = true,
                        textStyle = AppTextStyles.CaptionM.copy(letterSpacing = 1.sp),
                        trailingIcon = if (selected) R.drawable.ic_x else R.drawable.ic_plus_small,
                        onClick = { onClickMethod(option) },
                        modifier = Modifier
                            .alpha(if (selected) 1f else 0.5f)
                            .semantics { this.selected = selected }
                            .testTag("${testTagPrefix}Accept-${option.id}")
                    )
                }
            }
        }
    }
}

enum class PaykitPaymentOption(val id: String, val asset: PaykitAsset) {
    Savings("savings", PaykitAsset.BTC),
    Spending("spending", PaykitAsset.BTC),
    Usdt("usdt", PaykitAsset.USD),
    ;

    fun endpoints(available: List<String>): List<String> = available.filter {
        val method = MethodId.fromRawValue(it) ?: return@filter false
        when (this) {
            Savings -> method.isOnchain
            Spending -> method == MethodId.Bolt11 || method == MethodId.Lnurl
            Usdt -> method == MethodId.UsdtArbitrum
        }
    }
}
