package to.bitkit.models

import android.util.Log
import to.bitkit.env.Tag.APP
import to.bitkit.shared.AppError

sealed class ScannedOptions {
    data class Onchain(
        val address: String,
        val amount: Double?,
        val label: String?,
        val message: String?,
    ) : ScannedOptions()

    data class Bolt11(val invoice: String) : ScannedOptions()
    // TODO: Add other cases for lightning address, treasure hunt, auth, etc.
}

sealed class ScannedError(message: String) : AppError(message) {
    data object InvalidData : ScannedError("Invalid data")
    data object NoOptions : ScannedError("No options found in scanned data")
}

data class BIP21Data(
    val address: String,
    val amount: Double?,
    val label: String?,
    val message: String?,
    val lightningInvoice: String?,
    val other: Map<String, String>,
)

class ScannedData(uri: String) {
    val options: List<ScannedOptions>

    init {
        Log.d(APP, "Scanned data: $uri")

        if (uri.isEmpty()) throw ScannedError.InvalidData

        options = mutableListOf<ScannedOptions>().apply {
            if (listOf("lightning:", "lntb", "lnbc").any { uri.startsWith(it) }) {
                // Simple bolt11 invoice
                val invoice = uri.replace("lightning:", "")
                add(ScannedOptions.Bolt11(invoice))
            } else {
                // has BIP21 params
                val bip21Data = decodeBIP21(uri)
                if (bip21Data != null) {
                    bip21Data.lightningInvoice
                        ?.let {
                            add(ScannedOptions.Bolt11(it))
                        }
                        ?: add(
                            ScannedOptions.Onchain(
                                address = bip21Data.address,
                                amount = bip21Data.amount,
                                label = bip21Data.label,
                                message = bip21Data.message,
                            )
                        )
                }
            }

            if (isEmpty()) throw ScannedError.NoOptions
        }
    }

    private fun decodeBIP21(uri: String): BIP21Data? {
        if (!uri.startsWith("bitcoin:", ignoreCase = true)) return null

        val splitUri = uri.removePrefix("bitcoin:").split("?")
        val address = splitUri[0]
        if (address.isEmpty()) return null

        var amount: Double? = null
        var label: String? = null
        var message: String? = null
        var lightningInvoice: String? = null
        val other = mutableMapOf<String, String>()

        if (splitUri.size > 1) {
            val queryParams = splitUri[1].split("&")
            for (param in queryParams) {
                val keyValue = param.split("=")
                if (keyValue.size == 2) {
                    val key = keyValue[0].lowercase()
                    val value = keyValue[1]

                    when (key) {
                        "amount" -> amount = value.toDoubleOrNull()
                        "label" -> label = value
                        "message" -> message = value
                        "lightning" -> lightningInvoice = value
                        else -> other[key] = value
                    }
                }
            }
        }

        return BIP21Data(
            address = address,
            amount = amount,
            label = label,
            message = message,
            lightningInvoice = lightningInvoice,
            other = other
        )
    }
}
