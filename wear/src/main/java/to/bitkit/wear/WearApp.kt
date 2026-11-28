package to.bitkit.wear

import android.app.Application
import android.os.StrictMode
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
        if (Env.isDebug) logMainThreadBlocking()
        super.onCreate()
        if (Env.isDebug) Logger.init { filesDir }
    }

    /**
     * Debug builds log every disk or network access on the main thread to logcat under `StrictMode`, so a start
     * that blocks the main thread shows up while the journeys run.
     */
    private fun logMainThreadBlocking() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
    }
}
