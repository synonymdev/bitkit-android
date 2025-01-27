package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
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

    Column {
        Row(
            modifier = Modifier
                .background(Colors.White10, RoundedCornerShape(32.dp))
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = if (searchText.isNotEmpty()) Colors.Brand else Colors.White64,
            )
            Spacer(modifier = Modifier.width(8.dp))

            TextField(
                value = searchText,
                onValueChange = { viewModel.setSearchText(it) },
                placeholder = { Text(text = "Search") },
                modifier = Modifier
                    .weight(1f)
                    .padding(0.dp),
                colors = AppTextFieldDefaults.transparent,
            )
            Spacer(modifier = Modifier.width(12.dp))

            Row {
                Icon(
                    imageVector = Icons.Default.Tag,
                    contentDescription = null,
                    tint = if (selectedTags.isNotEmpty()) Colors.Brand else Colors.White64,
                    modifier = Modifier
                        .clickable {
                            onTagClick()
                        }
                )
                Spacer(modifier = Modifier.width(12.dp))

                Icon(
                    imageVector = Icons.Default.CalendarToday,
                    contentDescription = null,
                    tint = if (startDate != null) Colors.Brand else Colors.White64,
                    modifier = Modifier
                        .clickable {
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
