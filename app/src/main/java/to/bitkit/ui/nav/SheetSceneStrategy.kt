package to.bitkit.ui.nav

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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import kotlinx.coroutines.launch
import to.bitkit.ui.components.SheetDragHandle
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.Colors

private val sheetContainerColor = Color(0xFF141414)

@OptIn(ExperimentalMaterial3Api::class)
class SheetSceneStrategy<T : Any> : SceneStrategy<T> {

    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        val lastEntry = entries.lastOrNull()
        val sheetProperties = lastEntry?.metadata?.get(SHEET_KEY) as? SheetProperties
        return sheetProperties?.let { props ->
            @Suppress("UNCHECKED_CAST")
            BitKitSheetScene(
                key = lastEntry.contentKey as T,
                previousEntries = entries.dropLast(1),
                overlaidEntries = entries.dropLast(1),
                entry = lastEntry,
                sheetSize = props.size,
                onBack = onBack,
            )
        }
    }

    companion object Companion {
        fun sheet(size: SheetSize = SheetSize.LARGE): Map<String, Any> =
            mapOf(SHEET_KEY to SheetProperties(size))

        internal const val SHEET_KEY = "bitkit_sheet"
    }
}

data class SheetProperties(
    val size: SheetSize = SheetSize.LARGE,
)

@Composable
fun ColumnScope.SheetEntryContent(content: @Composable ColumnScope.() -> Unit) = run { content() }

@OptIn(ExperimentalMaterial3Api::class)
internal class BitKitSheetScene<T : Any>(
    override val key: T,
    override val previousEntries: List<NavEntry<T>>,
    override val overlaidEntries: List<NavEntry<T>>,
    private val entry: NavEntry<T>,
    private val sheetSize: SheetSize,
    private val onBack: () -> Unit,
) : OverlayScene<T> {

    override val entries: List<NavEntry<T>> = listOf(entry)

    override val content: @Composable (() -> Unit) = {
        val scope = rememberCoroutineScope()
        val scaffoldState = rememberBottomSheetScaffoldState(
            bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        )

        var isInitialExpansion by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            scaffoldState.bottomSheetState.expand()
            isInitialExpansion = false
        }

        LaunchedEffect(scaffoldState.bottomSheetState) {
            snapshotFlow { scaffoldState.bottomSheetState.currentValue }
                .collect { value: SheetValue ->
                    if (!isInitialExpansion && value == SheetValue.Hidden) {
                        onBack()
                    }
                }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            BottomSheetScaffold(
                scaffoldState = scaffoldState,
                sheetPeekHeight = 0.dp,
                sheetShape = AppShapes.sheet,
                sheetContent = {
                    Box(modifier = Modifier.sheetHeight(sheetSize)) {
                        entry.Content()
                    }
                },
                sheetDragHandle = { SheetDragHandle() },
                sheetContainerColor = sheetContainerColor,
                sheetContentColor = MaterialTheme.colorScheme.onSurface,
                containerColor = Color.Transparent,
            ) {
                BackHandler(enabled = scaffoldState.bottomSheetState.isVisible) {
                    scope.launch {
                        scaffoldState.bottomSheetState.hide()
                        onBack()
                    }
                }

                Scrim(isVisible = scaffoldState.bottomSheetState.targetValue != SheetValue.Hidden) {
                    scope.launch {
                        scaffoldState.bottomSheetState.hide()
                        onBack()
                    }
                }
            }
        }
    }
}

@Composable
private fun Scrim(
    isVisible: Boolean,
    onClick: () -> Unit,
) {
    val scrimAlpha by animateFloatAsState(
        targetValue = if (isVisible) 0.5f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "sheetScrimAlpha"
    )
    if (scrimAlpha > 0f || isVisible) {
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
