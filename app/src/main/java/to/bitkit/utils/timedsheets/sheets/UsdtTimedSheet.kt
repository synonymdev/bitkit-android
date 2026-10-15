package to.bitkit.utils.timedsheets.sheets

import kotlinx.coroutines.flow.first
import to.bitkit.data.SettingsStore
import to.bitkit.data.UsdtPreference
import to.bitkit.env.Env
import to.bitkit.ui.components.TimedSheetType
import to.bitkit.utils.timedsheets.TimedSheetItem
import javax.inject.Inject

class UsdtTimedSheet @Inject constructor(private val settingsStore: SettingsStore) : TimedSheetItem {
    override val type = TimedSheetType.USDT
    override val priority = type.priority

    override suspend fun shouldShow() = Env.isUsdtEnabled &&
        settingsStore.data.first().usdtPreference == UsdtPreference.UNDECIDED

    override suspend fun onShown() = Unit
    override suspend fun onDismissed() = Unit
}
