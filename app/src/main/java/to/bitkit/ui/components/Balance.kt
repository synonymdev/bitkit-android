package to.bitkit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import to.bitkit.ui.shared.moneyString

@Composable
fun BalanceView(
    label: String,
    value: ULong?,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Normal,
        )
        val valueText = value
            ?.let { moneyString(it.toLong(), null) }
            ?: "Loading…"
        Text(
            text = "$valueText",
            fontSize = 46.sp,
            fontWeight = FontWeight.Black,
        )
    }
}
