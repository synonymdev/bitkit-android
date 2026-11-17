package to.bitkit.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.di.json
import to.bitkit.services.WakeCredentials
import to.bitkit.services.WakeService
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps this install's wake credentials and registration state in the [Keychain]. They belong to the install, so they
 * are reused across launches, left out of backups, and deleted with the wallet.
 */
@Singleton
class WakeCredentialStore @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
    private val wakeService: WakeService,
) {
    companion object {
        private const val TAG = "WakeCredentialStore"
    }

    private val mutex = Mutex()

    /** The credentials, or null before this install first registered. */
    suspend fun load(): WakeCredentials? = withContext(ioDispatcher) {
        keychain.loadString(Keychain.Key.WAKE_CREDENTIALS.name)?.let { json.decodeFromString<WakeCredentials>(it) }
    }

    /** The credentials, generated and saved on first use. */
    suspend fun loadOrCreate(): WakeCredentials = withContext(ioDispatcher) {
        mutex.withLock {
            load() ?: wakeService.generateCredentials().also {
                keychain.upsertString(Keychain.Key.WAKE_CREDENTIALS.name, json.encodeToString(it))
                Logger.info("Created wake credentials", context = TAG)
            }
        }
    }

    suspend fun registrationState(): WakeRegistrationState = withContext(ioDispatcher) {
        keychain.loadString(Keychain.Key.WAKE_REGISTRATION.name)
            ?.let { json.decodeFromString<WakeRegistrationState>(it) }
            ?: WakeRegistrationState()
    }

    suspend fun updateRegistrationState(transform: (WakeRegistrationState) -> WakeRegistrationState) {
        withContext(ioDispatcher) {
            mutex.withLock {
                val next = transform(registrationState())
                keychain.upsertString(Keychain.Key.WAKE_REGISTRATION.name, json.encodeToString(next))
            }
        }
    }
}

/** What this install last sent to the gateway. */
@Serializable
data class WakeRegistrationState(
    /** Unix seconds of the last registration sent, answered or not, so the next one is later. */
    val lastTimestampSecs: Long? = null,
    /**
     * Hash of the last registration the gateway accepted. Cleared before each registration is sent, so one whose answer
     * was lost is sent again, and null while this install is not registered.
     */
    val fingerprint: String? = null,
    /**
     * Set before a registration is sent and cleared once the gateway confirms the unregister: a registration whose
     * answer was lost may still be stored there, so an opt-out has to unregister it.
     */
    val mayBeRegistered: Boolean = false,
)
