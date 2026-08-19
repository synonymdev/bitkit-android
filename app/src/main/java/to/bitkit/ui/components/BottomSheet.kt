package to.bitkit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import to.bitkit.ui.appViewModel
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    shape: Shape = AppShapes.sheet,
    containerColor: Color = Colors.Black,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(color = Colors.White08)
        ) {
            SheetDragHandle()
        }
    },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
    content: @Composable ColumnScope.() -> Unit,
) {
    val toastHazeState = rememberHazeState(blurEnabled = true)
    val app = appViewModel

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.semantics { testTagsAsResourceId = true },
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        scrimColor = scrimColor,
        dragHandle = dragHandle,
        contentWindowInsets = contentWindowInsets,
        properties = properties,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .hazeSource(toastHazeState, zIndex = 0f)
            ) {
                content()
            }
            if (app != null) {
                SheetToastHost(
                    app = app,
                    hazeState = toastHazeState,
                    modifier = Modifier.matchParentSize()
                )
            }
        }
    }
}

@Composable
private fun SheetToastHost(
    app: AppViewModel,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val currentToast by app.currentToast.collectAsStateWithLifecycle()
    ToastOverlay(
        toast = currentToast,
        onDismiss = { app.hideToast() },
        hazeState = hazeState,
        onDragStart = { app.pauseToast() },
        onDragEnd = { app.resumeToast() },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetPreview(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BottomSheet(
        onDismissRequest = {},
        sheetState = remember {
            SheetState(
                skipPartiallyExpanded = true,
                initialValue = SheetValue.Expanded,
                positionalThreshold = { 0f },
                velocityThreshold = { 0f },
            )
        },
        modifier = modifier,
        content = content,
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
