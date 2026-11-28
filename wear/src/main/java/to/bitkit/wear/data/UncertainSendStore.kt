package to.bitkit.wear.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A send this watch handed to Spark under the idempotency [key], whose outcome it has not learned. [sparkAddress] is
 * set for a transfer to a Spark address, and [expiresAtSecs] for an invoice: its expiry in epoch seconds.
 */
data class UncertainSend(
    val key: String,
    val sparkAddress: String? = null,
    val expiresAtSecs: ULong? = null,
)

/**
 * The sends this watch handed to Spark and has not seen the end of. Each is noted before it goes out and dropped once
 * Spark shows its payment or refuses it unsent, so a send that is still here may have paid: the app was closed during
 * it, or it failed without a trace. A retry then goes out under the same key, which Spark pays once.
 */
interface UncertainSendStore {
    /** Notes [send] before it goes out. Returns whether its key was noted already, by an earlier attempt. */
    suspend fun begin(send: UncertainSend): Boolean

    /** Drops the send under [key], whose outcome is known. */
    suspend fun settle(key: String)

    /** The key of the noted transfer to [sparkAddress], in either case, or null when there is none. */
    suspend fun transferKey(sparkAddress: String): String?
}

/**
 * [UncertainSendStore] in the watch's settings file, so the wallet's wipe clears it with the settings. An invoice's
 * send is dropped a day after the invoice expired, and at most [MAX_SENDS] are kept: the newest ones.
 */
@Singleton
class SettingsUncertainSendStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val nowSecs: () -> ULong,
) : UncertainSendStore {
    companion object {
        const val MAX_SENDS = 100

        /** How long after its invoice expired a send is still kept, in seconds. */
        const val EXPIRED_KEPT_SECS = 86_400uL
        private const val SEPARATOR = '|'
        private const val FIELDS = 4
        private const val MILLIS_PER_SEC = 1_000L
    }

    @Inject
    constructor(dataStore: DataStore<Preferences>) :
        this(dataStore, { (System.currentTimeMillis() / MILLIS_PER_SEC).toULong() })

    private val key = stringSetPreferencesKey("uncertain_sends")

    override suspend fun begin(send: UncertainSend): Boolean {
        val now = nowSecs()
        var wasNoted = false
        dataStore.edit { prefs ->
            val sends = decode(prefs[key])
            wasNoted = sends.any { it.send.key == send.key }
            if (wasNoted) return@edit
            prefs[key] = sends
                .filter { it.send.expiresAtSecs?.let { expiresAt -> expiresAt + EXPIRED_KEPT_SECS > now } != false }
                .plus(Entry(send.copy(sparkAddress = send.sparkAddress?.lowercase()), now))
                .sortedByDescending { it.startedAtSecs }
                .take(MAX_SENDS)
                .mapTo(HashSet()) { it.encode() }
        }
        return wasNoted
    }

    override suspend fun settle(key: String) {
        dataStore.edit { prefs ->
            val sends = decode(prefs[this.key])
            if (sends.none { it.send.key == key }) return@edit
            prefs[this.key] = sends.filter { it.send.key != key }.mapTo(HashSet()) { it.encode() }
        }
    }

    override suspend fun transferKey(sparkAddress: String): String? {
        val address = sparkAddress.lowercase()
        return decode(dataStore.data.first()[key]).firstOrNull { it.send.sparkAddress == address }?.send?.key
    }

    private class Entry(val send: UncertainSend, val startedAtSecs: ULong) {
        fun encode(): String = listOf(
            send.key,
            send.sparkAddress.orEmpty(),
            send.expiresAtSecs?.toString().orEmpty(),
            startedAtSecs.toString(),
        ).joinToString(SEPARATOR.toString())
    }

    private fun decode(entries: Set<String>?): List<Entry> = entries.orEmpty().mapNotNull { entry ->
        val fields = entry.split(SEPARATOR).takeIf { it.size == FIELDS } ?: return@mapNotNull null
        val startedAt = fields[3].toULongOrNull() ?: return@mapNotNull null
        val send = UncertainSend(
            key = fields[0],
            sparkAddress = fields[1].takeIf { it.isNotEmpty() },
            expiresAtSecs = fields[2].toULongOrNull(),
        )
        Entry(send, startedAt)
    }
}
