package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableSet
import to.bitkit.R
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun TagSelectorSheet() {
    val activity = activityListViewModel ?: return
    val app = appViewModel ?: return
    val availableTags by activity.availableTags.collectAsStateWithLifecycle()
    val selectedTags by activity.selectedTags.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        activity.updateAvailableTags()
    }

    Content(
        availableTags = availableTags,
        selectedTags = selectedTags.toImmutableSet(),
        onTagClick = {
            activity.toggleTag(it)
            app.hideSheet()
        },
    )
}

@Composable
private fun Content(
    availableTags: ImmutableList<String>,
    selectedTags: ImmutableSet<String>,
    onTagClick: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sheetHeight(SheetSize.SMALL)
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar(stringResource(R.string.wallet__tags_filter_title))

        Spacer(modifier = Modifier.height(42.dp))

        Caption13Up(
            text = stringResource(R.string.wallet__tags_filter),
            color = Colors.White64,
        )

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
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                availableTags = persistentListOf("Bitcoin", "Lightning", "Sent", "Received"),
                selectedTags = persistentSetOf("Bitcoin", "Received"),
            )
        }
    }
}
