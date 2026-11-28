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
     * Adds [cents] to the spend of [dayKey], an ISO date, unless that takes the day above [capCents]. Returns whether
     * it was added. A day earlier than the one last counted does not start a new count, so setting the watch's date
     * back frees nothing.
     */
    suspend fun reserve(dayKey: String, cents: Long, capCents: Long): Boolean
}

/** [QuickPaySpendStore] in the watch's settings file, so the wallet's wipe clears it with the settings. */
@Singleton
class SettingsQuickPaySpendStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : QuickPaySpendStore {
    private val dayKey = stringPreferencesKey("quickpay_spend_day")
    private val centsKey = longPreferencesKey("quickpay_spend_cents")

    override suspend fun reserve(dayKey: String, cents: Long, capCents: Long): Boolean {
        if (cents <= 0 || capCents <= 0) return false
        var isReserved = false
        dataStore.edit { prefs ->
            val countedDay = prefs[this.dayKey]
            val isNewDay = countedDay == null || dayKey > countedDay
            val spent = if (isNewDay) 0L else prefs[centsKey] ?: 0L
            if (spent < 0 || cents > capCents - spent) return@edit
            prefs[this.dayKey] = if (isNewDay) dayKey else requireNotNull(countedDay)
            prefs[centsKey] = spent + cents
            isReserved = true
        }
        return isReserved
    }
}
