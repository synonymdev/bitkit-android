package to.bitkit.repositories

import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import to.bitkit.data.PrivatePaykitCacheStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.json

internal class PrivatePaykitStateStore(
    private val keychain: Keychain,
    private val cacheStore: PrivatePaykitCacheStore,
) {
    private var state: PrivatePaykitState? = null

    fun currentState(): PrivatePaykitState? = state

    fun replaceState(newState: PrivatePaykitState) {
        state = newState
    }

    suspend fun ensureState(): PrivatePaykitState {
        state?.let { return it }
        val secretState = runCatching {
            keychain.loadString(Keychain.Key.PRIVATE_PAYKIT_SECRET_STATE.name)
                ?.let { json.decodeFromString<PrivatePaykitSecretState>(it) }
        }.getOrNull() ?: PrivatePaykitSecretState()
        val cacheState = cacheStore.data.first()

        return PrivatePaykitState(secretState, cacheState).also { state = it }
    }

    suspend fun persistState(
        markWalletBackup: Boolean,
        notifyBackupStateChanged: () -> Unit,
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
                    cleanupPending = stored.cleanupPending,
                    deletedContactCleanupPendingPublicKeys = stored.deletedContactCleanupPendingPublicKeys,
                )
            }
            if (markWalletBackup) notifyBackupStateChanged()
        }.getOrElse { throw PrivatePaykitError.StatePersistenceFailed(it) }
    }
}
