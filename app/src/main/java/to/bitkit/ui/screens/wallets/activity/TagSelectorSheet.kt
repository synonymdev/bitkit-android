package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
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
import to.bitkit.R
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.VerticalSpacer
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
        selectedTags = selectedTags,
        onTagClick = {
            activity.toggleTag(it)
            app.hideSheet()
        },
    )
}

@Composable
private fun Content(
    availableTags: List<String>,
    selectedTags: Set<String>,
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

        VerticalSpacer(42.dp)

        Caption13Up(
            text = stringResource(R.string.wallet__tags_filter),
            color = Colors.White64,
        )

        VerticalSpacer(16.dp)

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

        FillHeight()
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                availableTags = listOf("Bitcoin", "Lightning", "Sent", "Received"),
                selectedTags = setOf("Bitcoin", "Received"),
            )
        }
    }
}
