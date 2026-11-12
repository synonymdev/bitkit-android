package to.bitkit.utils.timedsheets.sheets

import kotlinx.coroutines.flow.first
import to.bitkit.data.SettingsStore
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.MoveToSparkMath
import to.bitkit.repositories.SparkRepo
import to.bitkit.ui.components.TimedSheetType
import to.bitkit.utils.timedsheets.TimedSheetItem
import javax.inject.Inject

/** Shown once after the update to users whose channels still hold a balance worth moving to Spark. */
class MoveToSparkTimedSheet @Inject constructor(
    private val settingsStore: SettingsStore,
    private val lightningRepo: LightningRepo,
    private val sparkRepo: SparkRepo,
) : TimedSheetItem {
    override val type = TimedSheetType.MOVE_TO_SPARK
    override val priority = type.priority

    override suspend fun shouldShow(): Boolean {
        if (settingsStore.data.first().hasSeenMoveToSparkIntro) return false
        return MoveToSparkMath.canMove(lightningRepo.lightningState.value.channels, sparkRepo.state.value)
    }

    override suspend fun onShown() = settingsStore.update { it.copy(hasSeenMoveToSparkIntro = true) }
    override suspend fun onDismissed() = Unit
}
