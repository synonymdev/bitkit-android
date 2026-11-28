package to.bitkit.wear.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton

/** What the watch's QuickPay payments added up to today, in US cents, for the daily limit. */
interface QuickPaySpendStore {
    /**
     * Adds [cents] to the spend of [dayKey], an ISO date, unless that takes the day above [capCents]. Returns the day
     * it was added to, or null when it was not. A day earlier than the one last counted does not start a new count:
     * the payment is added to the day last counted, so setting the watch's date back frees nothing.
     */
    suspend fun reserve(dayKey: String, cents: Long, capCents: Long): String?

    /** Takes [cents] out of the spend of [dayKey] again, when that is still the day counted. */
    suspend fun release(dayKey: String, cents: Long)
}

/** [QuickPaySpendStore] in the watch's settings file, so the wallet's wipe clears it with the settings. */
@Singleton
class SettingsQuickPaySpendStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : QuickPaySpendStore {
    private val dayKey = stringPreferencesKey("quickpay_spend_day")
    private val centsKey = longPreferencesKey("quickpay_spend_cents")

    override suspend fun reserve(dayKey: String, cents: Long, capCents: Long): String? {
        if (cents <= 0 || capCents <= 0) return null
        var reservedDay: String? = null
        dataStore.edit { prefs ->
            val countedDay = prefs[this.dayKey]
            val day = if (countedDay == null || dayKey > countedDay) dayKey else countedDay
            val spent = if (day == countedDay) prefs[centsKey] ?: 0L else 0L
            if (spent < 0 || cents > capCents - spent) return@edit
            prefs[this.dayKey] = day
            prefs[centsKey] = spent + cents
            reservedDay = day
        }
        return reservedDay
    }

    override suspend fun release(dayKey: String, cents: Long) {
        if (cents <= 0) return
        dataStore.edit { prefs ->
            if (prefs[this.dayKey] != dayKey) return@edit
            prefs[centsKey] = ((prefs[centsKey] ?: 0L) - cents).coerceAtLeast(0L)
        }
    }
}
