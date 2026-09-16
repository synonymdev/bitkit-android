package to.bitkit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.LocalBottomSheetOverlayState
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@OptIn(ExperimentalMaterial3Api::class)
@Stable
class BottomSheetOverlayState {
    private val sheetEntries = mutableStateListOf<BottomSheetOverlayEntry>()

    internal val entries: List<BottomSheetOverlayEntry>
        get() = sheetEntries

    internal fun show(entry: BottomSheetOverlayEntry) {
        val index = sheetEntries.indexOfFirst { it.key === entry.key }
        if (index >= 0) {
            sheetEntries[index] = entry
        } else {
            sheetEntries += entry
        }
    }

    internal fun hide(key: Any) {
        sheetEntries.removeAll { it.key === key }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
internal data class BottomSheetOverlayEntry(
    val key: Any,
    val modifier: Modifier,
    val sheetState: SheetState,
    val onDismissRequest: () -> Unit,
    val content: @Composable ColumnScope.() -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val overlayState = checkNotNull(LocalBottomSheetOverlayState.current) {
        "BottomSheet must be composed inside BottomSheetOverlayHost"
    }
    val sheetKey = remember { Any() }
    val entry = BottomSheetOverlayEntry(
        key = sheetKey,
        modifier = modifier.semantics { testTagsAsResourceId = true },
        sheetState = sheetState,
        onDismissRequest = onDismissRequest,
        content = content,
    )

    SideEffect { overlayState.show(entry) }
    DisposableEffect(overlayState, sheetKey) {
        onDispose { overlayState.hide(sheetKey) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetOverlayHost(
    state: BottomSheetOverlayState,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        state.entries.forEach { entry ->
            key(entry.key) {
                SheetHost(
                    shouldExpand = true,
                    onDismiss = entry.onDismissRequest,
                    scaffoldContainerColor = Color.Transparent,
                    sheetDragHandle = { ModalSheetDragHandle() },
                    sheetContainerColor = Colors.Black,
                    sheetState = entry.sheetState,
                    sheets = {
                        entry.content(this)
                    },
                    content = {},
                    modifier = entry.modifier,
                )
            }
        }
    }
}

@Composable
private fun ModalSheetDragHandle() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(color = Colors.White08)
    ) {
        SheetDragHandle()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetPreview(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    SheetHost(
        shouldExpand = true,
        sheetState = remember {
            SheetState(
                skipPartiallyExpanded = true,
                initialValue = SheetValue.Expanded,
                positionalThreshold = { 0f },
                velocityThreshold = { 0f },
            )
        },
        sheetDragHandle = { ModalSheetDragHandle() },
        sheetContainerColor = Colors.Black,
        sheets = content,
        content = {},
        modifier = modifier,
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Column(
                modifier = Modifier
                    .sheetHeight(isModal = true)
                    .gradientBackground()
                    .padding(horizontal = 16.dp)
            ) {
                SheetTopBar("Page Title")
                FillHeight()
                PrimaryButton(text = "Button", onClick = {})
                VerticalSpacer(24.dp)
            }
        }
    }
}
