package to.bitkit.ui.screens.transfer.external

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.Toast
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.components.LargeRow
import to.bitkit.ui.components.NumberPadSimple
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun ExternalFeeCustomScreen(
    viewModel: ExternalNodeViewModel,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val currency = currencyViewModel ?: return
    val scope = rememberCoroutineScope()

    val context = LocalContext.current

    var input by remember {
        mutableStateOf(uiState.customFeeRate?.toString() ?: "")
    }

    LaunchedEffect(input) {
        val feeRate = input.toUIntOrNull() ?: 0u
        viewModel.onCustomFeeRateChange(feeRate)
    }

    val totalFeeText = remember(uiState.networkFee) {
        if (uiState.networkFee == 0L) {
            ""
        } else {
            currency.convert(uiState.networkFee)
                ?.let {
                    context.getString(R.string.wallet__send_fee_total_fiat)
                        .replace("{feeSats}", "${uiState.networkFee}")
                        .replace("{fiatSymbol}", it.symbol)
                        .replace("{fiatFormatted}", it.formatted)
                } ?: context.getString(R.string.wallet__send_fee_total).replace("{feeSats}", "${uiState.networkFee}")
        }
    }

    Content(
        input = input,
        totalFeeText = totalFeeText,
        onKeyPress = { key ->
            when (key) {
                KEY_DELETE -> input = if (input.isNotEmpty()) input.dropLast(1) else ""
                else -> if (input.length < 3) input = (input + key).trimStart('0')
            }
        },
        onContinue = {
            val feeRate = input.toUIntOrNull() ?: 0u
            if (feeRate == 0u) {
                scope.launch {
                    ToastEventBus.send(
                        type = Toast.ToastType.INFO,
                        title = context.getString(R.string.wallet__min_possible_fee_rate),
                        description = context.getString(R.string.wallet__min_possible_fee_rate_msg),
                    )
                }
                return@Content
            }
            onBack()
        },
        onBack = onBack,
        onClose = onClose,
    )
}

@Composable
private fun Content(
    input: String,
    totalFeeText: String,
    onKeyPress: (String) -> Unit = {},
    onContinue: () -> Unit = {},
    onBack: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    val feeRate = input.toUIntOrNull() ?: 0u
    val isValid = feeRate != 0u

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__external__nav_title),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClose) },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            VerticalSpacer(16.dp)
            Display(stringResource(R.string.lightning__transfer__custom_fee).withAccent(accentColor = Colors.Purple))

            FillHeight(1f)

            Column {
                Caption13Up(stringResource(R.string.common__sat_vbyte), color = Colors.White64)

                VerticalSpacer(16.dp)
                LargeRow(
                    prefix = null,
                    text = input.ifEmpty { "0" },
                    symbol = BITCOIN_SYMBOL,
                    showSymbol = true,
                )
                VerticalSpacer(8.dp)

                Column(
                    modifier = Modifier.height(22.dp)
                ) {
                    if (isValid) {
                        AnimatedVisibility(visible = totalFeeText.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                            BodyM(totalFeeText, color = Colors.White64)
                        }
                    }
                }
            }

            FillHeight(1f)

            NumberPadSimple(
                onPress = onKeyPress,
                modifier = Modifier.height(350.dp)
            )

            PrimaryButton(
                onClick = onContinue,
                text = stringResource(R.string.common__continue)
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            input = "5",
            totalFeeText = "₿ 256 for average transaction ($0.25)"
        )
    }
}
