package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DateRangePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeSelectorSheet() {
    val dateRangeState = rememberDateRangePickerState()
    val activityListViewModel = activityListViewModel ?: return
    val appViewModel = appViewModel ?: return

    DateRangeSelectorSheetContent(
        dateRangeState = dateRangeState,
        onClearClick = {
            dateRangeState.setSelection(null, null)
            activityListViewModel.clearDateRange()
            appViewModel.hideSheet()
        },
        onApplyClick = {
            activityListViewModel.setDateRange(
                startDate = dateRangeState.selectedStartDateMillis,
                endDate = dateRangeState.selectedEndDateMillis,
            )
            appViewModel.hideSheet()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeSelectorSheetContent(
    dateRangeState: DateRangePickerState,
    onClearClick: () -> Unit = {},
    onApplyClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(.775f)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        DateRangePicker(
            state = dateRangeState,
            modifier = Modifier.weight(1f),
            showModeToggle = false,
            colors = DatePickerDefaults.colors(
                containerColor = Color.Transparent,
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
            SecondaryButton(
                onClick = onClearClick,
                text = "Clear",
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                onClick = onApplyClick,
                text = "Apply",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier.fillMaxSize()
        ) {
            DateRangeSelectorSheetContent(
                dateRangeState = rememberDateRangePickerState(),
            )
        }
    }
}
