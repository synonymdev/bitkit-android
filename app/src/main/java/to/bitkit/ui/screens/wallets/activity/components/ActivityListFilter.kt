package to.bitkit.ui.screens.wallets.activity.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.SearchInput
import to.bitkit.ui.components.SearchInputIconButton
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun ActivityListFilter(
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    hasTagFilter: Boolean,
    hasDateRangeFilter: Boolean,
    onTagClick: () -> Unit,
    onDateRangeClick: () -> Unit,
    tabs: List<ActivityTab>,
    currentTabIndex: Int,
    onTabChange: (ActivityTab) -> Unit,
    modifier: Modifier = Modifier,
    selectedTags: Set<String> = emptySet(),
    onRemoveTag: (String) -> Unit = {},
) {
    val focusManager = LocalFocusManager.current

    Column(modifier = modifier) {
        SearchInput(
            value = searchText,
            onValueChange = onSearchTextChange,
            modifier = Modifier.fillMaxWidth(),
            trailingContent = {
                if (selectedTags.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .sizeIn(maxWidth = 150.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        selectedTags.forEach { tag ->
                            TagButton(
                                text = tag,
                                onClick = { onRemoveTag(tag) },
                                isSelected = false,
                                displayIconClose = true,
                            )
                        }
                    }
                    HorizontalSpacer(12.dp)
                }

                SearchInputIconButton(
                    iconRes = R.drawable.ic_tag,
                    isActive = hasTagFilter,
                    onClick = {
                        focusManager.clearFocus()
                        onTagClick()
                    },
                    modifier = Modifier.testTag("TagsPrompt")
                )
                HorizontalSpacer(12.dp)
                SearchInputIconButton(
                    iconRes = R.drawable.ic_calendar,
                    isActive = hasDateRangeFilter,
                    onClick = {
                        focusManager.clearFocus()
                        onDateRangeClick()
                    },
                    modifier = Modifier.testTag("DatePicker")
                )
            }
        )

        VerticalSpacer(16.dp)

        CustomTabRowWithSpacing(
            tabs = tabs,
            currentTabIndex = currentTabIndex,
            onTabChange = onTabChange
        )
    }
}

enum class ActivityTab : TabItem {
    ALL, SENT, RECEIVED, OTHER;

    override val uiText: String
        @Composable
        get() = when (this) {
            ALL -> stringResource(R.string.wallet__activity_tabs__all)
            SENT -> stringResource(R.string.wallet__activity_tabs__sent)
            RECEIVED -> stringResource(R.string.wallet__activity_tabs__received)
            OTHER -> stringResource(R.string.wallet__activity_tabs__other)
        }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        ActivityListFilter(
            searchText = "",
            onSearchTextChange = {},
            hasTagFilter = false,
            onTagClick = {},
            hasDateRangeFilter = false,
            onDateRangeClick = {},
            tabs = ActivityTab.entries,
            currentTabIndex = 0,
            onTabChange = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview
@Composable
private fun PreviewWithTags() {
    AppThemeSurface {
        ActivityListFilter(
            searchText = "",
            onSearchTextChange = {},
            hasTagFilter = false,
            onTagClick = {},
            hasDateRangeFilter = false,
            onDateRangeClick = {},
            tabs = ActivityTab.entries,
            currentTabIndex = 0,
            onTabChange = {},
            selectedTags = setOf("Tag1", "Tag2"),
            modifier = Modifier.padding(16.dp)
        )
    }
}
