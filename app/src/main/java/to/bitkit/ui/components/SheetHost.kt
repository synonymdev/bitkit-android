package to.bitkit.ui.components

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.bitkit.ui.sheets.BackupRoute
import to.bitkit.ui.sheets.PinRoute
import to.bitkit.ui.sheets.SendRoute
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.Colors

enum class SheetSize { LARGE, MEDIUM, SMALL, CALENDAR; }

private val sheetContainerColor = Color(0xFF141414) // Equivalent to White08 on a Black background

@Stable
sealed interface Sheet {
    data class Send(val route: SendRoute = SendRoute.Recipient) : Sheet
    data object Receive : Sheet
    data class Pin(val route: PinRoute = PinRoute.Prompt()) : Sheet
    data class Backup(val route: BackupRoute = BackupRoute.ShowMnemonic) : Sheet
    data object ActivityDateRangeSelector : Sheet
    data object ActivityTagSelector : Sheet
    data class LnurlAuth(val domain: String, val lnurl: String, val k1: String) : Sheet
    data object ForceTransfer : Sheet
    data class Gift(val code: String, val amount: ULong) : Sheet

    data class TimedSheet(val type: TimedSheetType) : Sheet
}

/**@param priority Priority levels for timed sheets (higher number = higher priority)*/
enum class TimedSheetType(val priority: Int) {
    APP_UPDATE(priority = 5),
    BACKUP(priority = 4),
    NOTIFICATIONS(priority = 3),
    QUICK_PAY(priority = 2),
    HIGH_BALANCE(priority = 1)
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
            sheetDragHandle = { SheetDragHandle() },
            sheetContainerColor = sheetContainerColor,
            sheetContentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            content()

            // Dismiss on back
            BackHandler(enabled = scaffoldState.bottomSheetState.isVisible) {
                scope.launch {
                    scaffoldState.bottomSheetState.hide()
                    onDismiss()
                }
            }

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
