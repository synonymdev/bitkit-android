package to.bitkit.repositories

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.lightningdevkit.ldknode.Network
import to.bitkit.di.json as appJson

internal object PaykitIssuerInterop {
    /** Canonical lowercase Bitcoin asset used by Paykit Payment Requests. */
    const val BITCOIN_ASSET = "btc"

    private val payloadJson = Json(appJson) {
        prettyPrint = false
        isLenient = false
        encodeDefaults = false
    }

    fun supportedEndpointIdentifiers(
        identifiers: List<String>,
        network: Network,
    ): List<String> = identifiers
        .filter { MethodId.fromRawValue(it, network) != null }
        .distinct()

    fun parseEndpointPayload(endpointData: String): PaykitEndpointPayload? {
        val payload = runCatching {
            payloadJson.decodeFromString<PaykitEndpointPayload>(endpointData)
        }.getOrNull() ?: return null
        val value = payload.value.trim()
        if (value.isEmpty()) return null

        return payload.copy(value = value)
    }

    fun serializeEndpointPayload(value: String): String? {
        val trimmedValue = value.trim()
        if (trimmedValue.isEmpty()) return null

        return payloadJson.encodeToString(PaykitEndpointPayload(value = trimmedValue))
    }
}

@Serializable
internal data class PaykitEndpointPayload(
    val value: String,
    val min: String? = null,
    val max: String? = null,
)
