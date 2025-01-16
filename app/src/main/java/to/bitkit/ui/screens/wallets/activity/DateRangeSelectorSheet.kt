package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DateRangePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.ui.theme.Colors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeSelectorSheet(
    dateRangeState: DateRangePickerState,
    onClearClick: () -> Unit,
    onApplyClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(.875f)
            .padding(horizontal = 16.dp)
    ) {
        DateRangePicker(
            state = dateRangeState,
            modifier = Modifier.weight(1f),
            showModeToggle = false,
            colors = DatePickerDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                selectedDayContainerColor = Colors.Brand,
                dayInSelectionRangeContainerColor = Colors.Brand16,
            ),
        )
        Spacer(modifier = Modifier.height(32.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(vertical = 16.dp)
                .fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = onClearClick,
                shape = RoundedCornerShape(30.dp),
                border = BorderStroke(1.dp, Colors.White16),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
            ) {
                Text(
                    text = "Clear",
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = Colors.White80,
                )
            }
            Button(
                onClick = onApplyClick,
                colors = ButtonDefaults.buttonColors(containerColor = Colors.White16),
                shape = RoundedCornerShape(30.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
            ) {
                Text(
                    text = "Apply",
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = Colors.White,
                )
            }
        }
    }
}
