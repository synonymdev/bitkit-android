package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagSelectorSheet() {
    val activity = activityListViewModel ?: return
    val app = appViewModel ?: return
    val activityState by activity.activityState.collectAsStateWithLifecycle()
    val selectedTags by activity.selectedTags.collectAsStateWithLifecycle()

    TagSelectorSheetContent(
        availableTags = activityState.availableTags,
        selectedTags = selectedTags,
        onTagClick = { activity.toggleTag(it) },
        onClearClick = {
            activity.clearTags()
            app.hideSheet()
        },
        onApplyClick = {
            app.hideSheet()
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagSelectorSheetContent(
    availableTags: List<String>,
    selectedTags: Set<String>,
    onTagClick: (String) -> Unit = {},
    onClearClick: () -> Unit = {},
    onApplyClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(SheetSize.SMALL)
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar(stringResource(R.string.wallet__tags_filter_title))

        Spacer(modifier = Modifier.height(16.dp))

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            availableTags.forEach { tag ->
                TagButton(
                    text = tag,
                    onClick = { onTagClick(tag) },
                    isSelected = selectedTags.contains(tag)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
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

@OptIn(ExperimentalLayoutApi::class)
@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier.fillMaxSize()
        ) {
            TagSelectorSheetContent(
                availableTags = listOf("Bitcoin", "Lightning", "Sent", "Received"),
                selectedTags = setOf("Bitcoin", "Received"),
            )
        }
    }
}
