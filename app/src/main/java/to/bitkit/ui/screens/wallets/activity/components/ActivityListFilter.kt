package to.bitkit.ui.screens.wallets.activity.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.ActivityListViewModel

@Composable
fun ActivityListFilter(
    viewModel: ActivityListViewModel,
    onTagClick: () -> Unit,
    onDateRangeClick: () -> Unit,
) {
    val searchText by viewModel.searchText.collectAsState()
    val selectedTags by viewModel.selectedTags.collectAsState()
    val startDate by viewModel.startDate.collectAsState()

    val focusManager = LocalFocusManager.current

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(Colors.White10, MaterialTheme.shapes.large)
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_magnifying_glass),
                contentDescription = null,
                tint = if (searchText.isNotEmpty()) Colors.Brand else Colors.White64,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))

            TextField(
                value = searchText,
                onValueChange = { viewModel.setSearchText(it) },
                placeholder = { Text(text = stringResource(R.string.common__search)) },
                colors = AppTextFieldDefaults.transparent,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))

            Row {
                Icon(
                    painter = painterResource(R.drawable.ic_tag),
                    contentDescription = null,
                    tint = if (selectedTags.isNotEmpty()) Colors.Brand else Colors.White64,
                    modifier = Modifier
                        .size(24.dp)
                        .clickableAlpha {
                            focusManager.clearFocus()
                            onTagClick()
                        }
                )
                Spacer(modifier = Modifier.width(12.dp))

                Icon(
                    painter = painterResource(R.drawable.ic_calendar),
                    contentDescription = null,
                    tint = if (startDate != null) Colors.Brand else Colors.White64,
                    modifier = Modifier
                        .size(24.dp)
                        .clickableAlpha {
                            focusManager.clearFocus()
                            onDateRangeClick()
                        }
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Column {
            var selectedTab by remember { mutableStateOf(ActivityTab.ALL) }

            TabRow(selectedTabIndex = ActivityTab.entries.indexOf(selectedTab)) {
                ActivityTab.entries.forEach { tab ->
                    Tab(
                        text = { Text(tab.title) },
                        selected = selectedTab == tab,
                        onClick = {
                            selectedTab = tab
                            // TODO on tab change: update filtered activities
                        }
                    )
                }
            }
        }
    }
}

enum class ActivityTab {
    ALL, SENT, RECEIVED, OTHER;
}

val ActivityTab.title: String
    @Composable
    get() = when (this) {
        ActivityTab.ALL -> "All"
        ActivityTab.SENT -> "Sent"
        ActivityTab.RECEIVED -> "Received"
        ActivityTab.OTHER -> "Other"
    }
