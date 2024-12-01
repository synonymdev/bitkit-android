package to.bitkit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.R
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.shared.moneyString

@Composable
fun BalanceSummary(
    onSavingsClick: () -> Unit,
    onSpendingClick: () -> Unit,
) {
    val balances = LocalBalances.current
    BalanceView(
        label = stringResource(R.string.label_balance_total),
        value = balances.totalSats,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onSavingsClick)
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.label_savings),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Normal,
            )
            Text(
                text = moneyString(balances.totalOnchainSats.toLong(), null),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        VerticalDivider()
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onSpendingClick)
                .padding(4.dp)
        ) {
            Text(
                text = stringResource(R.string.label_spending),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Normal,
            )
            Text(
                text = moneyString(balances.totalLightningSats.toLong(), null),
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

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
