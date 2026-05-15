package to.bitkit.repositories

import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import to.bitkit.data.PrivatePaykitCacheStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.json
import to.bitkit.utils.Logger

internal class PrivatePaykitStateStore(
    private val keychain: Keychain,
    private val cacheStore: PrivatePaykitCacheStore,
) {
    companion object {
        private const val TAG = "PrivatePaykitStateStore"
    }

    private var state: PrivatePaykitState? = null

    fun currentState(): PrivatePaykitState? = state

    fun replaceState(newState: PrivatePaykitState) {
        state = newState
    }

    suspend fun ensureState(): PrivatePaykitState {
        state?.let { return it }
        val serializedSecretState = runCatching {
            keychain.loadString(Keychain.Key.PRIVATE_PAYKIT_SECRET_STATE.name)
        }.onFailure {
            Logger.warn("Failed to load private Paykit secret state", it, context = TAG)
        }.getOrNull()
        val secretState = serializedSecretState
            ?.let { serialized ->
                runCatching {
                    json.decodeFromString<PrivatePaykitSecretState>(serialized)
                }.onFailure {
                    Logger.warn("Failed to decode private Paykit secret state", it, context = TAG)
                }.getOrNull()
            } ?: PrivatePaykitSecretState()
        val cacheState = cacheStore.data.first()

        return PrivatePaykitState(secretState, cacheState).also { state = it }
    }

    suspend fun persistState(
        markWalletBackup: Boolean,
        notifyBackupStateChanged: () -> Unit,
        preserveCleanupMarkers: Boolean = true,
    ) {
        val current = state ?: return
        runCatching {
            val secretState = current.secretState()
            if (secretState.contacts.isEmpty()) {
                keychain.delete(Keychain.Key.PRIVATE_PAYKIT_SECRET_STATE.name)
            } else {
                keychain.upsertString(Keychain.Key.PRIVATE_PAYKIT_SECRET_STATE.name, json.encodeToString(secretState))
            }

            cacheStore.update { stored ->
                current.cacheState(
                    cleanupPending = if (preserveCleanupMarkers) stored.cleanupPending else false,
                    deletedContactCleanupPendingPublicKeys = if (preserveCleanupMarkers) {
                        stored.deletedContactCleanupPendingPublicKeys
                    } else {
                        emptySet()
                    },
                )
            }
            if (markWalletBackup) notifyBackupStateChanged()
        }.getOrElse { throw PrivatePaykitError.StatePersistenceFailed(it) }
    }
}
