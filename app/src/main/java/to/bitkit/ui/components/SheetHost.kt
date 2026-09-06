package to.bitkit.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import to.bitkit.models.SamRockSetupRequest
import to.bitkit.repositories.PaykitSubscriptionId
import to.bitkit.ui.screens.wallets.receive.ReceiveRoute
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.sheets.BackupRoute
import to.bitkit.ui.sheets.PinRoute
import to.bitkit.ui.sheets.SendRoute
import to.bitkit.ui.sheets.WidgetsRoute
import to.bitkit.ui.sheets.hardware.HardwareRoute
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.Colors

enum class SheetSize { LARGE, MEDIUM, COMPACT, SMALL, CALENDAR; }

val DefaultSheetContainerColor = Color(0xFF141414) // Equivalent to White08 on a Black background

enum class SheetHandlePlacement {
    ScaffoldSlot,
    ContentOverlay,
}

sealed interface SubscriptionRoute {
    data class Review(val id: PaykitSubscriptionId) : SubscriptionRoute
    data class Success(val id: PaykitSubscriptionId) : SubscriptionRoute
    data class Details(val id: PaykitSubscriptionId) : SubscriptionRoute
    data class Cancel(val id: PaykitSubscriptionId) : SubscriptionRoute
}

@Stable
sealed interface Sheet {
    data class Send(
        val route: SendRoute = SendRoute.Recipient,
        val hardwareWalletId: String? = null,
    ) : Sheet
    data class Receive(
        val route: ReceiveRoute = ReceiveRoute.QR,
        val hardwareWalletId: String? = null,
    ) : Sheet
    data object PaymentRequests : Sheet
    data class Subscription(val route: SubscriptionRoute) : Sheet
    data class Pin(val route: PinRoute = PinRoute.Prompt()) : Sheet
    data object ChangePin : Sheet
    data object DisablePin : Sheet
    data class Backup(val route: BackupRoute = BackupRoute.ShowMnemonic) : Sheet
    data class Hardware(val route: HardwareRoute = HardwareRoute.Intro) : Sheet
    data class Widgets(val route: WidgetsRoute = WidgetsRoute.Gallery) : Sheet
    data object ActivityDateRangeSelector : Sheet
    data object ActivityTagSelector : Sheet
    data class LnurlAuth(val domain: String, val lnurl: String, val k1: String) : Sheet
    data object ForceTransfer : Sheet
    data class Gift(val code: String, val amount: ULong) : Sheet
    data object ConnectionClosed : Sheet
    data class BTCPayConnection(
        val setup: SamRockSetupRequest,
        val isConnecting: Boolean = false,
        val errorText: String? = null,
    ) : Sheet
    data class QrScanner(val isPubkyScan: Boolean = false) : Sheet
    data class PubkyAuth(val authUrl: String) : Sheet

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
    visibilityKey: Any? = null,
    onVisible: () -> Unit = {},
    dismissEnabled: Boolean = true,
    sheetHandlePlacement: SheetHandlePlacement = SheetHandlePlacement.ScaffoldSlot,
    sheetContainerColor: Color = DefaultSheetContainerColor,
    sheets: @Composable ColumnScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentDismissEnabled by rememberUpdatedState(dismissEnabled)
    val currentShouldExpand by rememberUpdatedState(shouldExpand)
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { currentDismissEnabled || !currentShouldExpand || it != SheetValue.Hidden },
        )
    )
    var wasSheetVisible by remember { mutableStateOf(false) }
    var visibleKey by remember { mutableStateOf<Any?>(null) }

    // Automatically expand or hide the bottom sheet based on bool flag
    LaunchedEffect(shouldExpand) {
        if (shouldExpand) {
            scaffoldState.bottomSheetState.expand()
        } else {
            scaffoldState.bottomSheetState.hide()
        }
    }

    LaunchedEffect(scaffoldState.bottomSheetState.isVisible, visibilityKey) {
        if (scaffoldState.bottomSheetState.isVisible) {
            wasSheetVisible = true
            if (currentShouldExpand && visibleKey != visibilityKey) {
                visibleKey = visibilityKey
                onVisible()
            }
        } else if (wasSheetVisible) {
            val dismissedKey = visibleKey
            wasSheetVisible = false
            visibleKey = null
            if (dismissedKey == visibilityKey) onDismiss()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = 0.dp,
            sheetShape = AppShapes.sheet,
            sheetContent = {
                when (sheetHandlePlacement) {
                    SheetHandlePlacement.ScaffoldSlot -> sheets()
                    SheetHandlePlacement.ContentOverlay -> OverlayHandleSheetContent(sheets)
                }
            },
            sheetDragHandle = when (sheetHandlePlacement) {
                SheetHandlePlacement.ScaffoldSlot -> {
                    { SheetDragHandle() }
                }
                SheetHandlePlacement.ContentOverlay -> null
            },
            sheetContainerColor = sheetContainerColor,
            sheetContentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            content()

            // Dismiss on back
            BackHandler(enabled = scaffoldState.bottomSheetState.isVisible) {
                if (dismissEnabled) {
                    scope.launch {
                        scaffoldState.bottomSheetState.hide()
                        onDismiss()
                    }
                }
            }

            Scrim(scaffoldState.bottomSheetState, enabled = dismissEnabled) {
                scope.launch {
                    scaffoldState.bottomSheetState.hide()
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun OverlayHandleSheetContent(
    sheets: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            sheets()
        }

        SheetDragHandle(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(1f)
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun Scrim(
    bottomSheetState: SheetState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val isBottomSheetVisible = bottomSheetState.targetValue != SheetValue.Hidden
    val scrimAlpha by animateFloatAsState(
        targetValue = if (isBottomSheetVisible) 0.5f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "sheetScrimAlpha"
    )
    if (scrimAlpha > 0f || isBottomSheetVisible) {
        val interactionModifier = if (enabled) {
            Modifier.clickableAlpha(pressedAlpha = 1f, onClick = onClick)
        } else {
            Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Colors.Black.copy(alpha = scrimAlpha))
                .then(interactionModifier)
        )
    }
}
