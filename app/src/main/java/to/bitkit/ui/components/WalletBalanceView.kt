package to.bitkit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import to.bitkit.models.ConvertedAmount
import to.bitkit.ui.currencyViewModel
import to.bitkit.viewmodels.PrimaryDisplay

@Composable
fun RowScope.WalletBalanceView(
    title: String,
    sats: Long,
    icon: ImageVector,
    iconColor: Color,
    modifier: Modifier,
) {
    val currency = currencyViewModel ?: return
    val rates by currency.rates.collectAsState()
    val primaryDisplay by currency.primaryDisplay.collectAsState(PrimaryDisplay.BITCOIN)
    val converted: ConvertedAmount? = if (rates.isNotEmpty()) currency.convert(sats = sats) else null

    Column(
        modifier = Modifier
            .weight(1f)
            .then(modifier)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        converted?.let { converted ->
            if (primaryDisplay == PrimaryDisplay.BITCOIN) {
                val btcComponents = converted.bitcoinDisplay(currency.displayUnit)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = iconColor,
                        modifier = Modifier
                            .size(20.dp)
                    )
                    Text(
                        text = btcComponents.value,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = iconColor,
                        modifier = Modifier
                            .size(20.dp)
                    )
                    Text(
                        text = converted.symbol,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.alpha(0.6f)
                    )
                    Text(
                        text = converted.formatted,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
}
