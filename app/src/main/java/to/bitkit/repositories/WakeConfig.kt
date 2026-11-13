package to.bitkit.repositories

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import to.bitkit.data.SettingsStore
import to.bitkit.env.Env
import to.bitkit.ui.areNotificationsEnabled
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether wakes are on for this install. They are off unless the build has a gateway, never registered by E2E builds,
 * and opt-in: see [isWakeOptedIn].
 */
@Singleton
class WakeConfig @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
) {
    /** The gateway wakes go through, or null to leave wakes off. */
    val gatewayUrl: String? get() = Env.wakeGatewayUrl

    /** False in E2E builds, so wiped test installs leave no dead push tokens on the gateway. */
    val isRegistrationEnabled: Boolean get() = !Env.isE2eTest

    val optedIn: Flow<Boolean> = settingsStore.data
        .map {
            isWakeOptedIn(
                backgroundPaymentsEnabled = it.notificationsGranted,
                hasNotificationPermission = context.areNotificationsEnabled(),
            )
        }
        .distinctUntilChanged()

    suspend fun isOptedIn(): Boolean = optedIn.first()
}

/**
 * Who registers for wakes. Registering links the node id, the Pubky key and the push token on the gateway, so it is
 * opt-in: only with Background Payments on, whose switch is the notification permission the app last saw, and with
 * that permission still granted. Turning it off unregisters. Change this function to widen or narrow who registers.
 */
internal fun isWakeOptedIn(backgroundPaymentsEnabled: Boolean, hasNotificationPermission: Boolean): Boolean =
    backgroundPaymentsEnabled && hasNotificationPermission
