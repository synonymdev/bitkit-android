package to.bitkit.services.offline

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import to.bitkit.data.SettingsStore
import to.bitkit.env.Env
import to.bitkit.flags.OfflineReceiveFeatureFlags
import to.bitkit.models.OfflineReceiveDevSettings
import javax.inject.Inject
import javax.inject.Singleton

/** Effective offline receive configuration. `isEnabled` is false unless compiled in and toggled on. */
data class OfflineReceiveSettings(
    val isEnabled: Boolean = false,
    val settlementNodeId: String? = null,
    val witnessNodeIds: List<String> = emptyList(),
    val prepareTimeoutMillis: Long = DEFAULT_PREPARE_TIMEOUT_MILLIS,
) {
    /** Whether the node can be configured with a settlement peer. */
    val isConfigured: Boolean get() = isEnabled && !settlementNodeId.isNullOrBlank()

    companion object {
        const val DEFAULT_PREPARE_TIMEOUT_MILLIS = 60_000L
    }
}

@Singleton
class OfflineReceiveSettingsSource internal constructor(
    devSettings: Flow<OfflineReceiveDevSettings>,
    private val isNativeAvailable: Boolean,
) {
    @Inject
    constructor(settingsStore: SettingsStore) : this(
        devSettings = settingsStore.offlineReceiveDevSettings,
        isNativeAvailable = OfflineReceiveFeatureFlags.isNativeAvailable,
    )

    val settings: Flow<OfflineReceiveSettings> = devSettings.map { local ->
        OfflineReceiveSettings(
            isEnabled = isNativeAvailable && local.isEnabled,
            settlementNodeId = local.settlementNodeId?.takeIf { it.isNotBlank() } ?: defaultSettlementNodeId(),
            witnessNodeIds = local.witnessNodeIds.filter { it.isNotBlank() },
            prepareTimeoutMillis = local.prepareTimeoutMillis?.takeIf { it > 0 }
                ?: OfflineReceiveSettings.DEFAULT_PREPARE_TIMEOUT_MILLIS,
        )
    }

    suspend fun current(): OfflineReceiveSettings = settings.first()

    companion object {
        /** The Blocktank LSP node Bitkit already trusts on the current network. */
        fun defaultSettlementNodeId(): String? = Env.trustedLnPeers.firstOrNull()?.nodeId
    }
}
