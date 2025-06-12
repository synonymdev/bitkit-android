package to.bitkit.ui.screens.widgets.calculator.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import okhttp3.internal.toLongOrDefault
import to.bitkit.R
import to.bitkit.currentActivity
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.CurrencyViewModel
import kotlin.Boolean

@Composable
fun CalculatorCard(
    modifier: Modifier = Modifier,
    currencyViewModel: CurrencyViewModel,
    showWidgetTitle: Boolean,
) {

    val currencyUiState by currencyViewModel.uiState.collectAsStateWithLifecycle()
    var btcValue: String by remember { mutableStateOf("0") }
    var fiatValue: String by remember { mutableStateOf("0") }
    var fiatSymbol: String by remember { mutableStateOf("$") }

    CalculatorCardContent(
        modifier = modifier,
        showWidgetTitle = showWidgetTitle,
        btcValue = btcValue,
        onBTCChange = { newValue ->
            btcValue = newValue

            val fiat = currencyViewModel.convert(btcValue.toLongOrDefault(0L))
            fiatValue = fiat?.formatted.toString()
            fiatSymbol = fiat?.symbol.toString()
        },
        fiatSymbol = fiatSymbol,
        fiatName = currencyUiState.selectedCurrency,
        fiatValue = fiatValue,
        onFiatChange = { newValue ->
            fiatValue = newValue
            val satsValue = currencyViewModel.convertFiatToSats(fiatValue.toDoubleOrNull() ?: 0.0)
            btcValue = satsValue.toString()
        }
    )
}


@Composable
fun CalculatorCardContent(
    modifier: Modifier = Modifier,
    showWidgetTitle: Boolean,
    btcValue: String,
    onBTCChange: (String) -> Unit,
    fiatSymbol: String,
    fiatName: String,
    fiatValue: String,
    onFiatChange: (String) -> Unit,
) {

    Box(
        modifier = modifier
            .clip(shape = MaterialTheme.shapes.medium)
            .background(Colors.White10)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            if (showWidgetTitle) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("widget_title_row")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.widget_math_operation),
                        contentDescription = null,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("widget_title_icon"),
                        tint = Color.Unspecified
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    BodyMSB(
                        text = stringResource(R.string.widgets__calculator__name),
                        modifier = Modifier.testTag("widget_title_text")
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            CalculatorInput(
                modifier = Modifier.fillMaxWidth(),
                value = btcValue,
                onValueChange = onBTCChange,
                currencySymbol = BITCOIN_SYMBOL,
                currencyName = stringResource(R.string.settings__general__unit_bitcoin)
            )

            VerticalSpacer(16.dp)

            CalculatorInput(
                modifier = Modifier.fillMaxWidth(),
                value = fiatValue,
                onValueChange = onFiatChange,
                currencySymbol = fiatSymbol,
                currencyName = fiatName
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CalculatorCardContent(
                modifier = Modifier.fillMaxWidth(),
                showWidgetTitle = true,
                btcValue = "10000",
                onBTCChange = {},
                fiatSymbol = "$",
                fiatValue = "4.55",
                fiatName = "USD",
                onFiatChange = {}
            )

            CalculatorCardContent(
                modifier = Modifier.fillMaxWidth(),
                showWidgetTitle = false,
                btcValue = "10000",
                onBTCChange = {},
                fiatSymbol = "$",
                fiatValue = "4.55",
                fiatName = "USD",
                onFiatChange = {}
            )
        }
    }
}
