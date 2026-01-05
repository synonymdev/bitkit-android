package to.bitkit.utils.timedsheets

import to.bitkit.ui.components.TimedSheetType

interface TimedSheetItem {
    val type: TimedSheetType
    val priority: Int

    suspend fun shouldShow(): Boolean
    suspend fun onShown()
    suspend fun onDismissed()
}
