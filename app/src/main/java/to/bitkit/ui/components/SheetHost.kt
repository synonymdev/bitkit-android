package to.bitkit.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.bitkit.ui.screens.wallets.send.SendRoute
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.Colors

sealed class BottomSheetType {
    data class Send(val route: SendRoute = SendRoute.Options) : BottomSheetType()
    data object Receive : BottomSheetType()
    data object PinSetup : BottomSheetType()
    data object Backup : BottomSheetType()
    data object ActivityDateRangeSelector : BottomSheetType()
    data object ActivityTagSelector : BottomSheetType()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetHost(
    shouldExpand: Boolean,
    onDismiss: () -> Unit = {},
    sheets: @Composable ColumnScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    )

    // Automatically expand or hide the bottom sheet based on bool flag
    LaunchedEffect(shouldExpand) {
        if (shouldExpand) {
            scaffoldState.bottomSheetState.expand()
        } else {
            scaffoldState.bottomSheetState.hide()
        }
    }

    // Observe the state of the bottom sheet to invoke onDismiss callback
    // TODO prevent onDismiss call during first render
    LaunchedEffect(scaffoldState.bottomSheetState.isVisible) {
        if (!scaffoldState.bottomSheetState.isVisible) {
            onDismiss()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = 0.dp,
            sheetShape = AppShapes.sheet,
            sheetContent = sheets,
            sheetContainerColor = Colors.Gray6,
            sheetContentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            content()

            Scrim(scaffoldState.bottomSheetState) {
                scope.launch {
                    scaffoldState.bottomSheetState.hide()
                    onDismiss()
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun Scrim(
    bottomSheetState: SheetState,
    onClick: () -> Unit,
) {
    val isBottomSheetVisible = bottomSheetState.targetValue != SheetValue.Hidden
    val scrimAlpha by animateFloatAsState(
        targetValue = if (isBottomSheetVisible) 0.5f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "sheetScrimAlpha"
    )
    if (scrimAlpha > 0f || isBottomSheetVisible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Colors.Black.copy(alpha = scrimAlpha))
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = onClick,
                )
        )
    }
}
