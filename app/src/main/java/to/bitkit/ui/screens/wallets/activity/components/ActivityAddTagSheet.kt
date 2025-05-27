package to.bitkit.ui.screens.wallets.activity.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.ui.components.ModalBottomSheetHandle
import to.bitkit.ui.screens.wallets.send.AddTagContent
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.ActivityDetailViewModel
import to.bitkit.viewmodels.ActivityListViewModel
import to.bitkit.viewmodels.TagsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityAddTagSheet(
    listViewModel: ActivityListViewModel,
    activityViewModel: ActivityDetailViewModel,
    tagsViewModel: TagsViewModel = hiltViewModel(),
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AppShapes.sheet,
        containerColor = Colors.Black,
        dragHandle = { ModalBottomSheetHandle() },
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
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
            modifier = Modifier
                .height(400.dp)
                .gradientBackground()
        )
    }
}
