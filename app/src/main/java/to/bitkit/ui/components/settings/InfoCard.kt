package to.bitkit.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard

val cardColors: CardColors @Composable get() = CardDefaults.cardColors(containerColor = Colors.White10)

@Composable
fun InfoCard(
    header: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        SectionHeader(header, padding = PaddingValues.Zero)
        Card(
            colors = cardColors,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

@Composable
fun InfoCell(label: String, value: String, alignment: Alignment.Horizontal = Alignment.Start) {
    Column(horizontalAlignment = alignment) {
        Caption13Up(text = label, color = Colors.White64)
        VerticalSpacer(4.dp)
        BodySSB(text = value)
    }
}

@Composable
fun DetailRow(label: String, value: String, isError: Boolean = false) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Caption(
            text = label,
            color = Colors.White64,
            overflow = TextOverflow.MiddleEllipsis,
            maxLines = 1,
        )
        HorizontalSpacer(16.dp)
        Caption(
            text = value,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            overflow = TextOverflow.MiddleEllipsis,
            maxLines = 1,
            modifier = Modifier
                .weight(1f, fill = false)
                .clickableAlpha(onClick = copyToClipboard(value))
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            InfoCard(header = "Overview") {
                DetailRow("ID", "order-3c564573-ec4b-b502-5e6fe930435f")
                DetailRow("State", "PAID")
                DetailRow("Error", "Something went wrong", isError = true)
            }
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                InfoCell(label = "Amount", value = "123 456 sats")
                InfoCell(label = "Fees", value = "345 sats", alignment = Alignment.End)
            }
        }
    }
}
