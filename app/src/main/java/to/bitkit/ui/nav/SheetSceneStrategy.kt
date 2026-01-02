package to.bitkit.ui.nav

import androidx.compose.animation.AnimatedContent
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
        val rootIndex = entries.indexOfFirst { it.metadata[KEY_SHEET] != null }

        // No sheet root found - not a sheet flow
        if (rootIndex < 0) return null

        val rootEntry = entries[rootIndex]
        val props = rootEntry.metadata[KEY_SHEET] as? SheetProps ?: return null

        // Entries before the sheet root (to be overlaid)
        val entriesBeforeSheet = entries.take(rootIndex)

        // Number of entries in the sheet flow (for dismiss behavior)
        val entryCount = entries.size - rootIndex

        // Current entry's index within the sheet flow (for animation direction)
        val entryIndex = entries.size - 1

        @Suppress("UNCHECKED_CAST")
        return SheetScene(
            key = lastEntry.contentKey as T,
            previousEntries = entriesBeforeSheet,
            overlaidEntries = entriesBeforeSheet,
            size = props.size,
            entry = lastEntry,
            entryCount = entryCount,
            entryIndex = entryIndex,
            onBack = onBack,
        )
    }

    companion object {
        fun sheet(size: SheetSize = SheetSize.LARGE): Map<String, Any> = mapOf(
            KEY_SHEET to SheetProps(size),
        )

        internal const val KEY_SHEET = "bitkit_sheet"
    }
}

data class SheetProps(
    val size: SheetSize = SheetSize.LARGE,
)

private data class IndexedEntry<T : Any>(
    val index: Int,
    val entry: NavEntry<T>,
)

@Suppress("LongParameterList")
internal class SheetScene<T : Any>(
    override val key: T,
    override val previousEntries: List<NavEntry<T>>,
    override val overlaidEntries: List<NavEntry<T>>,
    private val size: SheetSize,
    private val entry: NavEntry<T>,
    private val entryCount: Int,
    private val entryIndex: Int,
    private val onBack: () -> Unit,
) : OverlayScene<T> {

    override val entries: List<NavEntry<T>> = listOf(entry)

    override val content: @Composable (() -> Unit) = {
        SheetHost(
            sheetSize = size,
            onDismiss = {
                // Pop all entries in the sheet flow to dismiss entire sheet
                repeat(entryCount) { onBack() }
            },
        ) {
            AnimatedContent(
                targetState = IndexedEntry(entryIndex, entry),
                transitionSpec = {
                    val isForward = targetState.index > initialState.index
                    if (isForward) {
                        Transitions.screenDefault.invoke(this)
                    } else {
                        Transitions.screenDefaultPop.invoke(this)
                    }
                },
                label = "SheetContentTransition",
            ) {
                it.entry.Content()
            }
        }
    }
}
