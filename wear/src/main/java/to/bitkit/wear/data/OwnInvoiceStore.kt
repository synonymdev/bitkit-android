package to.bitkit.wear.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The payment hashes of the invoices this wallet created and that can still be paid, which the wallet refuses to pay
 * itself. The watch adds each invoice it creates; a paired phone's hashes can be merged in with [add] too.
 */
interface OwnInvoiceStore {
    /** Remembers the invoice with [paymentHash], in hex, until [expiresAtSecs], its expiry in epoch seconds. */
    suspend fun add(paymentHash: String, expiresAtSecs: ULong)

    /** Whether [paymentHash], in hex of either case, is an unexpired invoice of this wallet. */
    suspend fun contains(paymentHash: String): Boolean
}

/**
 * [OwnInvoiceStore] in the watch's settings file, so the wallet's wipe clears it with the settings. Expired invoices
 * are dropped on every [add], as nobody can pay them, and at most [MAX_INVOICES] are kept: the ones that expire last.
 */
@Singleton
class SettingsOwnInvoiceStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val nowSecs: () -> ULong,
) : OwnInvoiceStore {
    companion object {
        const val MAX_INVOICES = 200
        private const val SEPARATOR = ':'
        private const val MILLIS_PER_SEC = 1_000L
    }

    @Inject
    constructor(dataStore: DataStore<Preferences>) :
        this(dataStore, { (System.currentTimeMillis() / MILLIS_PER_SEC).toULong() })

    private val key = stringSetPreferencesKey("own_invoices")

    override suspend fun add(paymentHash: String, expiresAtSecs: ULong) {
        val now = nowSecs()
        dataStore.edit { prefs ->
            val invoices = decode(prefs[key]) + (paymentHash.lowercase() to expiresAtSecs)
            prefs[key] = invoices.entries
                .filter { it.value > now }
                .sortedByDescending { it.value }
                .take(MAX_INVOICES)
                .mapTo(HashSet()) { "${it.key}$SEPARATOR${it.value}" }
        }
    }

    override suspend fun contains(paymentHash: String): Boolean {
        val expiresAt = decode(dataStore.data.first()[key])[paymentHash.lowercase()] ?: return false
        return expiresAt > nowSecs()
    }

    private fun decode(entries: Set<String>?): Map<String, ULong> = entries.orEmpty().mapNotNull { entry ->
        val expiresAt = entry.substringAfter(SEPARATOR, "").toULongOrNull() ?: return@mapNotNull null
        entry.substringBefore(SEPARATOR) to expiresAt
    }.toMap()
}
