package to.bitkit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import to.bitkit.models.ConvertedAmount
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.theme.Colors
import to.bitkit.models.PrimaryDisplay

@Composable
fun RowScope.WalletBalanceView(
    title: String,
    sats: Long,
    icon: Painter,
    modifier: Modifier,
) {
    val currency = currencyViewModel ?: return
    val (rates, _, _, _, displayUnit, primaryDisplay) = LocalCurrencies.current
    val converted: ConvertedAmount? = if (rates.isNotEmpty()) currency.convert(sats = sats) else null

    Column(
        modifier = Modifier
            .weight(1f)
            .then(modifier)
    ) {
        Text13Up(
            text = title,
            color = Colors.White64,
        )
        Spacer(modifier = Modifier.height(8.dp))
        converted?.let { converted ->
            if (primaryDisplay == PrimaryDisplay.BITCOIN) {
                val btcComponents = converted.bitcoinDisplay(displayUnit)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        painter = icon,
                        contentDescription = title,
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(24.dp)
                    )
                    BodyMSB(text = btcComponents.value)
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        painter = icon,
                        contentDescription = title,
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(24.dp)
                    )
                    BodyMSB(
                        text = converted.symbol,
                        modifier = Modifier.alpha(0.6f)
                    )
                    BodyMSB(text = converted.formatted)
                }
            }
        }
    }
}
