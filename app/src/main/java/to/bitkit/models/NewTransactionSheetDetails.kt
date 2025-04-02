package to.bitkit.models

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import to.bitkit.data.APP_PREFS
import to.bitkit.di.json
import to.bitkit.utils.Logger
import uniffi.bitkitcore.ActivityFilter
import uniffi.bitkitcore.PaymentType

@Serializable
data class NewTransactionSheetDetails(
    val type: NewTransactionSheetType,
    val direction: NewTransactionSheetDirection,
    val sats: Long,
) {
    companion object {
        private const val BACKGROUND_TRANSACTION_KEY = "backgroundTransaction"

        fun save(context: Context, details: NewTransactionSheetDetails) {
            val sharedPreferences = getSharedPreferences(context)
            val editor = sharedPreferences.edit()
            try {
                val jsonData = json.encodeToString(details)
                editor.putString(BACKGROUND_TRANSACTION_KEY, jsonData)
                editor.apply()
            } catch (e: Exception) {
                Logger.error("Failed to cache transaction", e)
            }
        }

        fun load(context: Context): NewTransactionSheetDetails? {
            val sharedPreferences = getSharedPreferences(context)
            val jsonData = sharedPreferences.getString(BACKGROUND_TRANSACTION_KEY, null) ?: return null

            return try {
                json.decodeFromString(jsonData)
            } catch (e: Exception) {
                Logger.error("Failed to load cached transaction", e)
                null
            }
        }

        fun clear(context: Context) {
            val sharedPreferences = getSharedPreferences(context)
            sharedPreferences.edit().remove(BACKGROUND_TRANSACTION_KEY).apply()
        }

        private fun getSharedPreferences(context: Context): SharedPreferences {
            return context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        }
    }
}

@Serializable
enum class NewTransactionSheetType {
    ONCHAIN, LIGHTNING
}

@Serializable
enum class NewTransactionSheetDirection {
    SENT, RECEIVED
}

fun NewTransactionSheetDirection.toTxType(): PaymentType = if (this == NewTransactionSheetDirection.SENT) {
    PaymentType.SENT
} else {
    PaymentType.RECEIVED
}

fun NewTransactionSheetType.toActivityFilter(): ActivityFilter {
    return if (this == NewTransactionSheetType.ONCHAIN) {
        ActivityFilter.ONCHAIN
    } else {
        ActivityFilter.LIGHTNING
    }
}

