package to.bitkit.utils.timedsheets.sheets

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.BuildConfig
import to.bitkit.di.BgDispatcher
import to.bitkit.services.AppUpdaterService
import to.bitkit.ui.components.TimedSheetType
import to.bitkit.utils.Logger
import to.bitkit.utils.timedsheets.TimedSheetItem
import javax.inject.Inject

class AppUpdateTimedSheet @Inject constructor(
    private val appUpdaterService: AppUpdaterService,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
) : TimedSheetItem {
    override val type = TimedSheetType.APP_UPDATE
    override val priority = 5

    override suspend fun shouldShow(): Boolean = withContext(bgDispatcher) {
        runCatching {
            val androidReleaseInfo = appUpdaterService.getReleaseInfo().platforms.android
            val currentBuildNumber = BuildConfig.VERSION_CODE

            if (androidReleaseInfo.buildNumber <= currentBuildNumber) return@withContext false
            if (androidReleaseInfo.isCritical) return@withContext false
        }.onFailure {
            Logger.warn("Failure fetching new releases", e = it, context = TAG)
        }.fold(
            onSuccess = { true },
            onFailure = { false },
        )
    }

    override suspend fun onShown() {
        Logger.debug("App update sheet shown", context = TAG)
    }

    override suspend fun onDismissed() {
        Logger.debug("App update sheet dismissed", context = TAG)
    }

    companion object {
        private const val TAG = "AppUpdateTimedSheet"
    }
}
