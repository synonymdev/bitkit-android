package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
            var selectedTab by remember { mutableStateOf(0) }
            val tabs = listOf("All", "Sent", "Received", "Other")
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        text = { Text(title) },
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            // TODO on tab change
                        }
                    )
                }
            }
        }
    }
}
