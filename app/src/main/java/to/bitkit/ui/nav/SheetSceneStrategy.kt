package to.bitkit.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import to.bitkit.ui.components.SheetHost
import to.bitkit.ui.components.SheetSize

class SheetSceneStrategy<T : Any> : SceneStrategy<T> {

    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        val lastEntry = entries.lastOrNull() ?: return null

        // Find the sheet root (first entry with sheet metadata)
        val sheetRootIndex = entries.indexOfFirst {
            it.metadata?.get(KEY_SHEET) != null
        }

        // No sheet root found - not a sheet flow
        if (sheetRootIndex < 0) return null

        val sheetRootEntry = entries[sheetRootIndex]
        val sheetProps = sheetRootEntry.metadata?.get(KEY_SHEET) as? SheetProperties ?: return null

        // Entries before the sheet root (to be overlaid)
        val entriesBeforeSheet = entries.take(sheetRootIndex)

        // Number of entries in the sheet flow (for dismiss behavior)
        val sheetEntryCount = entries.size - sheetRootIndex

        // Current entry's index within the sheet flow (for animation direction)
        val currentEntryIndex = entries.size - 1

        @Suppress("UNCHECKED_CAST")
        return SheetScene(
            key = lastEntry.contentKey as T,
            previousEntries = entriesBeforeSheet,
            overlaidEntries = entriesBeforeSheet,
            entry = lastEntry,
            sheetSize = sheetProps.size,
            onBack = onBack,
            sheetEntryCount = sheetEntryCount,
            entryIndex = currentEntryIndex,
        )
    }

    companion object {
        fun sheet(size: SheetSize = SheetSize.LARGE): Map<String, Any> = mapOf(
            KEY_SHEET to SheetProperties(size),
        )

        internal const val KEY_SHEET = "bitkit_sheet"
    }
}

data class SheetProperties(
    val size: SheetSize = SheetSize.LARGE,
)

private const val MS_ANIM_DURATION = 300

private data class IndexedEntry<T : Any>(
    val index: Int,
    val entry: NavEntry<T>,
)

@Suppress("LongParameterList")
internal class SheetScene<T : Any>(
    override val key: T,
    override val previousEntries: List<NavEntry<T>>,
    override val overlaidEntries: List<NavEntry<T>>,
    private val entry: NavEntry<T>,
    private val sheetSize: SheetSize,
    private val onBack: () -> Unit,
    private val sheetEntryCount: Int,
    private val entryIndex: Int,
) : OverlayScene<T> {

    override val entries: List<NavEntry<T>> = listOf(entry)

    override val content: @Composable (() -> Unit) = {
        SheetHost(
            sheetSize = sheetSize,
            onDismiss = {
                // Pop all entries in the sheet flow to dismiss entire sheet
                repeat(sheetEntryCount) { onBack() }
            },
        ) {
            AnimatedContent(
                targetState = IndexedEntry(entryIndex, entry),
                transitionSpec = {
                    val isForward = targetState.index > initialState.index
                    if (isForward) {
                        // Forward navigation: slide in from right, slide out to left
                        slideInHorizontally(
                            initialOffsetX = { it },
                            animationSpec = tween(MS_ANIM_DURATION, easing = FastOutSlowInEasing)
                        ) + fadeIn(
                            animationSpec = tween(MS_ANIM_DURATION, easing = FastOutSlowInEasing),
                            initialAlpha = 0.8f
                        ) togetherWith slideOutHorizontally(
                            targetOffsetX = { -it / 3 },
                            animationSpec = tween(MS_ANIM_DURATION, easing = FastOutSlowInEasing)
                        ) + fadeOut(
                            animationSpec = tween(MS_ANIM_DURATION, easing = FastOutSlowInEasing),
                            targetAlpha = 0.8f
                        )
                    } else {
                        // Backward navigation: slide in from left, slide out to right
                        slideInHorizontally(
                            initialOffsetX = { -it / 3 },
                            animationSpec = tween(MS_ANIM_DURATION, easing = FastOutSlowInEasing)
                        ) + fadeIn(
                            animationSpec = tween(MS_ANIM_DURATION, easing = FastOutSlowInEasing),
                            initialAlpha = 0.8f
                        ) togetherWith slideOutHorizontally(
                            targetOffsetX = { it },
                            animationSpec = tween(MS_ANIM_DURATION, easing = FastOutSlowInEasing)
                        )
                    }
                },
                label = "SheetContentTransition",
            ) { indexedEntry ->
                indexedEntry.entry.Content()
            }
        }
    }
}
