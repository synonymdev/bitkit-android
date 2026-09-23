package to.bitkit.models

/** Device-local developer settings for offline receive. Never backed up, default disabled. */
data class OfflineReceiveDevSettings(
    val isEnabled: Boolean = false,
    /** Overrides the default settlement node id (the first trusted Blocktank LSP peer of the network). */
    val settlementNodeId: String? = null,
    val witnessNodeIds: List<String> = emptyList(),
    val prepareTimeoutMillis: Long? = null,
)
