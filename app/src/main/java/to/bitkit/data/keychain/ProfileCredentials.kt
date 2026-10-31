package to.bitkit.data.keychain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import to.bitkit.utils.AppError
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** A local erase target; it conveys neither remote authority nor acknowledged logout. */
internal class ProfileCredentials internal constructor(
    internal val store: ProfileCredentialStore,
    internal val walletIndex: Long,
    private val values: Map<Preferences.Key<String>, ByteArray?>,
) {
    private val current = AtomicBoolean(true)

    internal fun requireCurrent() {
        if (!current.get()) throw ProfileCredentialsChanged()
    }

    internal fun invalidate() {
        current.set(false)
    }

    internal fun matches(current: Map<Preferences.Key<String>, ByteArray?>): Boolean =
        values.keys == current.keys && values.all { (key, expected) ->
            val actual = current[key]
            if (expected == null || actual == null) {
                expected == null && actual == null
            } else {
                MessageDigest.isEqual(expected, actual)
            }
        }
}

/** The caller retains identity admission across capture or erase, including the durable commit. */
internal class ProfileCredentialStore(
    private val store: DataStore<Preferences>,
    private val walletIndex: suspend () -> Long,
    private val decrypt: (String) -> ByteArray,
) {
    suspend fun capture(requireCurrent: () -> Unit): ProfileCredentials {
        requireCurrent()
        val index = walletIndex()
        val values = read(store.data.first(), index)
        requireCurrent()
        if (walletIndex() != index) throw ProfileCredentialsChanged()
        return ProfileCredentials(this, index, values)
    }

    suspend fun erase(target: ProfileCredentials, requireCurrent: () -> Unit) {
        requireCurrent()
        if (target.store !== this) throw ProfileCredentialsChanged()
        target.requireCurrent()
        var validated = false
        try {
            store.edit { preferences ->
                validateOwnedCredentials(target, requireCurrent) {
                    read(preferences, target.walletIndex)
                }
                keys(target.walletIndex).forEach(preferences::remove)
                validated = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!validated) target.invalidate()
            throw e
        }
    }

    /** Read-only admission check before using the captured grant for remote cleanup. */
    suspend fun validate(target: ProfileCredentials, requireCurrent: () -> Unit) {
        requireCurrent()
        if (target.store !== this) throw ProfileCredentialsChanged()
        validateOwnedCredentials(target, requireCurrent) { read(store.data.first(), target.walletIndex) }
    }

    private suspend fun validateOwnedCredentials(
        target: ProfileCredentials,
        requireCurrent: () -> Unit,
        values: suspend () -> Map<Preferences.Key<String>, ByteArray?>,
    ) {
        target.requireCurrent()
        try {
            requireCurrent()
            if (walletIndex() != target.walletIndex || !target.matches(values())) throw ProfileCredentialsChanged()
            requireCurrent()
            if (walletIndex() != target.walletIndex) throw ProfileCredentialsChanged()
            target.requireCurrent()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            target.invalidate()
            throw e
        }
    }

    private fun read(preferences: Preferences, index: Long): Map<Preferences.Key<String>, ByteArray?> =
        keys(index).associateWith { key -> preferences[key]?.let(decrypt) }

    private fun keys(index: Long): List<Preferences.Key<String>> = listOf(
        Keychain.Key.PAYKIT_SESSION,
        Keychain.Key.PUBKY_SECRET_KEY,
        Keychain.Key.SHARED_PUBKY_SOURCE,
    ).map { stringPreferencesKey("${it.name}_$index") }
}

internal class ProfileCredentialsChanged : AppError("The profile credentials changed before local cleanup")
