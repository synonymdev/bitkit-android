package to.bitkit.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.models.ConvertedAmount
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.viewmodels.PrimaryDisplay

@Composable
fun BalanceHeaderView(
    sats: Long,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    showBitcoinSymbol: Boolean = true,
) {
    val currency = currencyViewModel ?: return
    val (rates, _, _, _, displayUnit, primaryDisplay) = LocalCurrencies.current
    val converted: ConvertedAmount? = if (rates.isNotEmpty()) currency.convert(sats = sats) else null

    var isPressed by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.Start,
        modifier = modifier
            .graphicsLayer {
                this.alpha = if (isPressed) 0.5f else 1f
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = {
                        currency.togglePrimaryDisplay()
                    }
                )
            }
    ) {
        converted?.let { converted ->
            if (primaryDisplay == PrimaryDisplay.BITCOIN) {
                Column {
                    SmallRow(
                        prefix = prefix,
                        text = "${converted.symbol} ${converted.formatted}"
                    )

                    // large row
                    val btcComponents = converted.bitcoinDisplay(displayUnit)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(62.dp)
                    ) {
                        if (prefix != null) {
                            Text(
                                text = prefix,
                                fontSize = 46.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.alpha(0.6f)
                                    .padding(end = 8.dp)
                            )
                        }
                        if (showBitcoinSymbol) {
                            Text(
                                text = btcComponents.symbol,
                                fontSize = 46.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier
                                    .alpha(0.6f)
                                    .padding(end = 8.dp)
                            )
                        }
                        Text(
                            text = btcComponents.value,
                            fontSize = 46.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                }
            } else {
                Column {
                    val btcComponents = converted.bitcoinDisplay(displayUnit)
                    SmallRow(
                        prefix = prefix,
                        text = "${btcComponents.symbol} ${btcComponents.value}"
                    )

                    // large row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(62.dp)
                    ) {
                        if (prefix != null) {
                            Text(
                                text = prefix,
                                fontSize = 46.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier
                                    .alpha(0.6f)
                                    .padding(end = 8.dp)
                            )
                        }
                        Text(
                            text = converted.symbol,
                            fontSize = 46.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier
                                .alpha(0.6f)
                                .padding(end = 8.dp)
                        )
                        Text(
                            text = converted.formatted,
                            fontSize = 46.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallRow(prefix: String?, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(24.dp)
            .padding(bottom = 4.dp)
    ) {
        if (prefix != null) {
            Text(
                text = prefix,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.alpha(0.6f)
            )
        }
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.alpha(0.6f)
        )
    }
}
