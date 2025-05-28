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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.theme.Colors

@Composable
fun RowScope.WalletBalanceView(
    title: String,
    sats: Long,
    icon: Painter,
    modifier: Modifier,
) {
    val settings = settingsViewModel ?: return
    val currency = currencyViewModel ?: return
    val (_, _, _, _, displayUnit, primaryDisplay) = LocalCurrencies.current
    val converted: ConvertedAmount? = currency.convert(sats = sats)

    val hideBalance by settings.hideBalance.collectAsStateWithLifecycle()

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
                    BodyMSB(text = if (hideBalance) "• • • • •" else btcComponents.value)
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
                    BodyMSB(text = converted.symbol)
                    BodyMSB(text = if (hideBalance) "• • • • •" else converted.formatted)
                }
            }
        }
    }
}
