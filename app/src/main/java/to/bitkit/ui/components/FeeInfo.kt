package to.bitkit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import to.bitkit.ui.theme.Colors

@Composable
fun RowScope.FeeInfo(
    label: String,
    amount: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .weight(1f)
            .padding(top = 16.dp)
    ) {
        Caption13Up(
            text = label,
            color = Colors.White64,
        )
        Spacer(modifier = Modifier.height(8.dp))
        MoneySSB(sats = amount)
        Spacer(modifier = Modifier.weight(1f))
        HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
    }
}
