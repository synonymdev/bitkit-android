package to.bitkit.services

import com.synonym.paykit.FfiPaymentEndpoint
import com.synonym.paykit.FfiPrivatePaymentList
import com.synonym.paykit.paykitParsePrivatePaymentListJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import to.bitkit.di.json

internal object PrivatePaykitMessageParser {
    private const val LEGACY_PRIVATE_PAYMENTS_KIND = "paykit.private_payments"

    private val parserJson = Json(json) {
        prettyPrint = false
        encodeDefaults = false
    }

    fun parsePrivatePaymentListJson(
        rawJson: String,
        parsePaymentList: (String) -> FfiPrivatePaymentList = ::paykitParsePrivatePaymentListJson,
    ): FfiPrivatePaymentList? =
        runCatching { parsePaymentList(rawJson) }
            .getOrElse { parseLegacyPrivatePayments(rawJson) }

    private fun parseLegacyPrivatePayments(rawJson: String): FfiPrivatePaymentList? =
        runCatching {
            val envelope = parserJson.decodeFromString<LegacyPrivatePaymentsEnvelope>(rawJson)
            if (envelope.kind != LEGACY_PRIVATE_PAYMENTS_KIND) return null

            FfiPrivatePaymentList(
                paymentEndpoints = envelope.entries.map {
                    FfiPaymentEndpoint(
                        paymentEndpointIdentifier = it.key,
                        paymentEndpointPayload = it.value,
                    )
                },
            )
        }.getOrNull()
}

@Serializable
private data class LegacyPrivatePaymentsEnvelope(
    val kind: String,
    @SerialName("entries")
    val entries: Map<String, String> = emptyMap(),
)
