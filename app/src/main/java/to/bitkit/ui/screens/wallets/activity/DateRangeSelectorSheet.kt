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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeSelectorSheet() {
    val activity = activityListViewModel ?: return
    val app = appViewModel ?: return

    val startDate by activity.startDate.collectAsState()
    val endDate by activity.endDate.collectAsState()

    val dateRangeState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = startDate,
        initialSelectedEndDateMillis = endDate,
    )

    DateRangeSelectorSheetContent(
        dateRangeState = dateRangeState,
        onClearClick = {
            dateRangeState.setSelection(null, null)
            activity.clearDateRange()
            app.hideSheet()
        },
        onApplyClick = {
            activity.setDateRange(
                startDate = dateRangeState.selectedStartDateMillis,
                endDate = dateRangeState.selectedEndDateMillis,
            )
            app.hideSheet()
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
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        DateRangePicker(
            state = dateRangeState,
            showModeToggle = false,
            colors = DatePickerDefaults.colors(
                containerColor = Color.Transparent,
                selectedDayContainerColor = Colors.Brand,
                dayInSelectionRangeContainerColor = Colors.Brand16,
            ),
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            SecondaryButton(
                onClick = onClearClick,
                text = stringResource(R.string.wallet__filter_clear),
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                onClick = onApplyClick,
                text = stringResource(R.string.wallet__filter_apply),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
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
