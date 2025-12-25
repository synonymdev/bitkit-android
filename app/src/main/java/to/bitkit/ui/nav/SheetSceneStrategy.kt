package to.bitkit.ui.nav

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
        val lastEntry = entries.lastOrNull()
        val sheetProperties = lastEntry?.metadata?.get(SHEET_KEY) as? SheetProperties
        return sheetProperties?.let { props ->
            @Suppress("UNCHECKED_CAST")
            SheetScene(
                key = lastEntry.contentKey as T,
                previousEntries = entries.dropLast(1),
                overlaidEntries = entries.dropLast(1),
                entry = lastEntry,
                sheetSize = props.size,
                onBack = onBack,
            )
        }
    }

    companion object {
        fun sheet(size: SheetSize = SheetSize.LARGE): Map<String, Any> = mapOf(
            SHEET_KEY to SheetProperties(size),
        )

        internal const val SHEET_KEY = "bitkit_sheet"
    }
}

data class SheetProperties(
    val size: SheetSize = SheetSize.LARGE,
)

internal class SheetScene<T : Any>(
    override val key: T,
    override val previousEntries: List<NavEntry<T>>,
    override val overlaidEntries: List<NavEntry<T>>,
    private val entry: NavEntry<T>,
    private val sheetSize: SheetSize,
    private val onBack: () -> Unit,
) : OverlayScene<T> {

    override val entries: List<NavEntry<T>> = listOf(entry)

    override val content: @Composable (() -> Unit) = {
        SheetHost(
            sheetSize = sheetSize,
            onDismiss = onBack,
        ) {
            entry.Content()
        }
    }
}
