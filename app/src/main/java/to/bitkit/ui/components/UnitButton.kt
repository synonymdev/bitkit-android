package to.bitkit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun UnitButton(
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    color: Color = Colors.Brand,
) {
    val currency = currencyViewModel
    val currencies = LocalCurrencies.current

    Surface(
        color = Colors.White10,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .clickable(
                onClick = {
                    currency?.togglePrimaryDisplay()
                    onClick()
                }
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 5.dp, horizontal = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.SwapVert,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Caption13Up(
                text = if (currencies.primaryDisplay == PrimaryDisplay.BITCOIN) "BTC" else currencies.selectedCurrency,
                color = color,
            )
        }
    }
}

@Preview
@Composable
private fun UnitButtonPreview() {
    AppThemeSurface {
        Box(modifier = Modifier.padding(16.dp)) {
            UnitButton()
        }
    }
}
