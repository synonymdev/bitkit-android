package to.bitkit.wear

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import to.bitkit.wear.data.ForegroundMonitor
import to.bitkit.wear.env.Env
import to.bitkit.wear.utils.Logger
import javax.inject.Inject

@HiltAndroidApp
class WearApp : Application() {
    /** Injected here so it follows the process lifecycle from the first activity on. */
    @Inject
    lateinit var foregroundMonitor: ForegroundMonitor

    override fun onCreate() {
        super.onCreate()
        if (Env.isDebug) Logger.init(filesDir)
    }
}
