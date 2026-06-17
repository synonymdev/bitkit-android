package to.bitkit.services

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide flag tracking whether [to.bitkit.androidServices.LightningNodeService] is running as a
 * foreground service. Used to coordinate notification ownership: when the foreground service is alive
 * it handles LDK events in-process, so the background [to.bitkit.fcm.WakeNodeWorker] defers to it
 * instead of posting its own (duplicate) notification.
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
