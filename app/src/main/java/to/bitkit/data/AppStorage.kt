package to.bitkit.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.reflect.KProperty

const val APP_PREFS = "bitkit_prefs"

// TODO use dataStore
class AppStorage @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    val sharedPreferences: SharedPreferences
        get() = appContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)

    var onchainAddress: String by SharedPrefDelegate(Key.ONCHAIN_ADDRESS)
    var bolt11: String by SharedPrefDelegate(Key.BOLT11)
    var bip21: String by SharedPrefDelegate(Key.BIP21)

    enum class Key {
        ONCHAIN_ADDRESS,
        BOLT11,
        BIP21,
    }
}

private class SharedPrefDelegate(private val key: AppStorage.Key) {
    operator fun getValue(thisRef: AppStorage, property: KProperty<*>): String {
        return thisRef.sharedPreferences.getString(key.name, "") ?: ""
    }

    operator fun setValue(thisRef: AppStorage, property: KProperty<*>, value: String) {
        thisRef.sharedPreferences.edit().putString(key.name, value).apply()
    }
}
