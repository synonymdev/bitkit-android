package to.bitkit.data.keychain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import to.bitkit.utils.AppError
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** A local erase target; it conveys neither remote authority nor acknowledged logout. */
internal class ProfileCredentials internal constructor(
    internal val store: ProfileCredentialStore,
    internal val walletIndex: Long,
    private val values: Map<Preferences.Key<String>, ByteArray?>,
) {
    private enum class Invalidation { UNREADABLE, CHANGED }

    private val invalidation = AtomicReference<Invalidation?>(null)

    internal fun requireCurrent() {
        if (invalidation.get() != null) throw ProfileCredentialsChanged()
    }

    internal fun requireLocalErase() {
        if (invalidation.get() == Invalidation.CHANGED) throw ProfileCredentialsChanged()
    }

    internal fun invalidate() {
        invalidation.set(Invalidation.CHANGED)
    }

    internal fun markUnreadable() {
        invalidation.compareAndSet(null, Invalidation.UNREADABLE)
    }

    internal fun matches(key: Preferences.Key<String>, actual: ByteArray?): Boolean {
        if (!values.containsKey(key)) return false
        val expected = values[key]
        return if (expected == null || actual == null) {
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

    suspend fun erase(target: ProfileCredentials, requireCurrent: () -> Unit) =
        erase(target, requireCurrent, localOnly = false)

    /** A fresh local confirmation can recheck unreadable original bytes without restoring remote permission. */
    suspend fun eraseAfterLocalConfirmation(target: ProfileCredentials, requireCurrent: () -> Unit) =
        erase(target, requireCurrent, localOnly = true)

    private suspend fun erase(target: ProfileCredentials, requireCurrent: () -> Unit, localOnly: Boolean) {
        validateAdmission(target, requireCurrent)
        if (target.store !== this) throw ProfileCredentialsChanged()
        if (localOnly) target.requireLocalErase() else target.requireCurrent()
        var validationStarted = false
        try {
            store.edit { preferences ->
                validationStarted = true
                validateOwnedCredentials(target, requireCurrent, localOnly) {
                    preferences
                }
                keys(target.walletIndex).forEach(preferences::remove)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!validationStarted) target.markUnreadable()
            throw e
        }
    }

    /** Read-only admission check before using the captured grant for remote cleanup. */
    suspend fun validate(target: ProfileCredentials, requireCurrent: () -> Unit) {
        validateAdmission(target, requireCurrent)
        if (target.store !== this) throw ProfileCredentialsChanged()
        validateOwnedCredentials(target, requireCurrent) { store.data.first() }
    }

    private suspend fun validateOwnedCredentials(
        target: ProfileCredentials,
        requireCurrent: () -> Unit,
        localOnly: Boolean = false,
        values: suspend () -> Preferences,
    ) {
        if (localOnly) target.requireLocalErase() else target.requireCurrent()
        validateAdmission(target, requireCurrent)
        validateWallet(target)
        val preferences = readOwnedState(target, values)
        keys(target.walletIndex).forEach { key ->
            val actual = readOwnedState(target) { preferences[key]?.let(decrypt) }
            if (!target.matches(key, actual)) {
                target.invalidate()
                throw ProfileCredentialsChanged()
            }
        }
        validateAdmission(target, requireCurrent)
        validateWallet(target)
        if (localOnly) target.requireLocalErase() else target.requireCurrent()
    }

    private suspend fun validateWallet(target: ProfileCredentials) {
        if (readOwnedState(target) { walletIndex() } != target.walletIndex) {
            target.invalidate()
            throw ProfileCredentialsChanged()
        }
    }

    private fun validateAdmission(target: ProfileCredentials, requireCurrent: () -> Unit) {
        try {
            requireCurrent()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            target.invalidate()
            throw e
        }
    }

    private suspend fun <T> readOwnedState(target: ProfileCredentials, read: suspend () -> T): T = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        target.markUnreadable()
        throw e
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
