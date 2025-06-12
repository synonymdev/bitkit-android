package to.bitkit.ui.screens.widgets.calculator.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun CalculatorInput(
    value: String,
    onValueChange: (String) -> Unit,
    currencySymbol: String,
    currencyName: String,
    modifier: Modifier = Modifier
) {
    TextInput(
        value = value,
        onValueChange = onValueChange,
        leadingIcon = {
            Box(
                modifier = Modifier
                    .background(color = Colors.White10, shape = CircleShape)
                    .size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                BodyMSB(currencySymbol, color = Colors.Brand)
            }
        },
        suffix = { CaptionB(currencyName.uppercase(), color = Colors.Gray1) },
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CalculatorInput(
                modifier = Modifier.fillMaxWidth(),
                value = "100000",
                onValueChange = {},
                currencySymbol = "₿",
                currencyName = "BITCOIN"
            )
            CalculatorInput(
                modifier = Modifier.fillMaxWidth(),
                value = "4.55",
                onValueChange = {},
                currencySymbol = "$",
                currencyName = "USD"
            )
        }
    }
}
