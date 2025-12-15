package to.bitkit.ui.screens.wallets.activity.components

import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.ui.components.BottomSheet
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.screens.wallets.send.AddTagContent
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.ActivityDetailViewModel
import to.bitkit.viewmodels.ActivityListViewModel
import to.bitkit.viewmodels.AddTagUiState
import to.bitkit.viewmodels.TagsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityAddTagSheet(
    listViewModel: ActivityListViewModel,
    activityViewModel: ActivityDetailViewModel,
    tagsViewModel: TagsViewModel = hiltViewModel(),
    onDismiss: () -> Unit,
) {
    val uiState by tagsViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        tagsViewModel.loadTagSuggestions()
    }

    DisposableEffect(Unit) {
        onDispose {
            listViewModel.updateAvailableTags()
            tagsViewModel.onInputUpdated("")
        }
    }

    BottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding()
    ) {
        AddTagContent(
            uiState = uiState,
            onTagSelected = { tag ->
                activityViewModel.addTag(tag)
                onDismiss()
            },
            onTagConfirmed = { tag ->
                if (tag.isNotBlank()) {
                    activityViewModel.addTag(tag)
                    onDismiss()
                }
            },
            onInputUpdated = { newText -> tagsViewModel.onInputUpdated(newText) },
            onBack = onDismiss,
            focusOnShow = true,
            tagInputTestTag = "TagInput",
            addButtonTestTag = "ActivityTagsSubmit",
            modifier = Modifier
                .semantics { testTagsAsResourceId = true }
                .sheetHeight(SheetSize.SMALL, isModal = true)
                .gradientBackground()
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview(
            modifier = Modifier.imePadding()
        ) {
            AddTagContent(
                uiState = AddTagUiState(),
                onTagSelected = {},
                onTagConfirmed = {},
                onInputUpdated = {},
                onBack = {},
                modifier = Modifier
                    .sheetHeight(SheetSize.SMALL, isModal = true)
                    .gradientBackground()
            )
        }
    }
}
