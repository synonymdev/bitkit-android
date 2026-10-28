package to.bitkit.services

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide flag tracking whether [to.bitkit.androidServices.LightningNodeService] is running as a
 * foreground service. When it is alive it handles LDK events in-process.
 */
@Singleton
class NodeServiceFgState @Inject constructor() {
    @Volatile
    var isForegroundServiceRunning = false
        private set

    fun setForegroundServiceRunning(running: Boolean) {
        isForegroundServiceRunning = running
    }
}
