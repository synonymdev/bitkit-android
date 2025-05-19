package to.bitkit.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import to.bitkit.di.json
import to.bitkit.models.BalanceState
import to.bitkit.models.Suggestion
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KProperty

const val APP_PREFS = "bitkit_prefs"

// TODO use dataStore
@Singleton
class AppStorage @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    val sharedPreferences: SharedPreferences
        get() = appContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)

    private val _removedSuggestionsFlow = MutableStateFlow<List<String>>(emptyList())
    val removedSuggestionsFlow: Flow<List<String>> = _removedSuggestionsFlow.asStateFlow()

    init {
        _removedSuggestionsFlow.value = getRemovedSuggestionList()
    }

    var onchainAddress: String by SharedPrefDelegate(Key.ONCHAIN_ADDRESS)
    var bolt11: String by SharedPrefDelegate(Key.BOLT11)
    var bip21: String by SharedPrefDelegate(Key.BIP21)

    fun cacheBalance(balanceState: BalanceState) {
        try {
            val jsonData = json.encodeToString(balanceState)
            sharedPreferences.edit { putString(Key.BALANCE.name, jsonData) }

        } catch (e: Throwable) {
            Logger.debug("Failed to cache balance: $e")
        }
    }

    fun loadBalance(): BalanceState? {
        val jsonData = sharedPreferences.getString(Key.BALANCE.name, null) ?: return null

        return try {
            json.decodeFromString(jsonData)
        } catch (e: Throwable) {
            Logger.debug("Failed to load cached balance: $e")
            null
        }
    }

    fun addSuggestionToRemovedList(suggestion: Suggestion) {
        val removedSuggestions =
            sharedPreferences.getStringSet(Key.REMOVED_SUGGESTION.name, setOf<String>()).orEmpty().toMutableList()
        if (removedSuggestions.contains(suggestion.name)) return

        removedSuggestions.add(suggestion.name)

        sharedPreferences.edit {
            putStringSet(Key.REMOVED_SUGGESTION.name, removedSuggestions.toSet())
        }

        _removedSuggestionsFlow.value = removedSuggestions
    }

    private fun getRemovedSuggestionList() =
        sharedPreferences.getStringSet(Key.REMOVED_SUGGESTION.name, setOf<String>()).orEmpty().toList()

    enum class Key {
        ONCHAIN_ADDRESS,
        BOLT11,
        BIP21,
        BALANCE,
        REMOVED_SUGGESTION
    }

    fun clear() {
        sharedPreferences.edit { clear() }
        _removedSuggestionsFlow.value = emptyList()
    }
}

@Suppress("unused")
private class SharedPrefDelegate(private val key: AppStorage.Key) {
    operator fun getValue(thisRef: AppStorage, property: KProperty<*>): String {
        return thisRef.sharedPreferences.getString(key.name, "") ?: ""
    }

    operator fun setValue(thisRef: AppStorage, property: KProperty<*>, value: String) {
        thisRef.sharedPreferences.edit { putString(key.name, value) }
    }
}
