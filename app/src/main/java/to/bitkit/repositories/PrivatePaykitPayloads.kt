package to.bitkit.repositories

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import to.bitkit.di.json
import java.security.MessageDigest

internal object PrivatePaykitPayloads {
    private const val MAX_NOISE_PAYLOAD_BYTES = 1000
    private const val PRIVATE_ENDPOINT_REMOVAL_PAYLOAD = """{"value":""}"""
    private const val PRIVATE_PAYMENTS_ENVELOPE_KIND = "paykit.private_payments"
    private const val PRIVATE_PAYMENTS_REFERENCE_PLACEHOLDER = "550e8400-e29b-41d4-a716-446655440000"

    private val noisePayloadJson = Json(json) {
        prettyPrint = false
    }

    fun entriesWithinNoiseLimit(endpoints: List<Endpoint>): PrivatePaykitPayloadSelection {
        val entries = endpoints.map { StoredPaymentEntry(it.methodId.rawValue, it.rawPayload) }
        if (isNoisePayloadWithinLimit(entries)) return PrivatePaykitPayloadSelection(entries)

        val onchainOnlyEntries = entries.filter { it.methodId != MethodId.Bolt11.rawValue }
        if (onchainOnlyEntries.size < entries.size && onchainOnlyEntries.isNotEmpty()) {
            if (isNoisePayloadWithinLimit(onchainOnlyEntries)) {
                return PrivatePaykitPayloadSelection(entries = onchainOnlyEntries, droppedLightning = true)
            }
        }

        throw PrivatePaykitError.PayloadTooLarge
    }

    fun privateEndpointRemovalEntries(): List<StoredPaymentEntry> =
        MethodId.entries
            .filter { it.isBitkitManaged }
            .map { StoredPaymentEntry(it.rawValue, PRIVATE_ENDPOINT_REMOVAL_PAYLOAD) }

    fun validateNoisePayload(entries: List<StoredPaymentEntry>) {
        if (!isNoisePayloadWithinLimit(entries)) throw PrivatePaykitError.PayloadTooLarge
    }

    fun localPayloadHash(entries: List<StoredPaymentEntry>): String {
        val payload = entries.sortedBy { it.methodId }
            .joinToString(separator = "") {
                "${it.methodId.length}:${it.methodId}${it.endpointData.length}:${it.endpointData}"
            }
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.encodeToByteArray())
            .joinToString(separator = "") { "%02x".format(it) }
    }

    fun storedPaymentEntries(endpoints: Map<String, String>): List<StoredPaymentEntry> =
        endpoints.toSortedMap().map { StoredPaymentEntry(it.key, it.value) }

    private fun isNoisePayloadWithinLimit(entries: List<StoredPaymentEntry>): Boolean {
        val payload = entries.associate { it.methodId to it.endpointData }
        val envelope = PrivatePaymentsEnvelope(
            version = 1,
            kind = PRIVATE_PAYMENTS_ENVELOPE_KIND,
            reference = PRIVATE_PAYMENTS_REFERENCE_PLACEHOLDER,
            entries = payload,
        )
        return noisePayloadJson.encodeToString(envelope).encodeToByteArray().size <= MAX_NOISE_PAYLOAD_BYTES
    }
}

@kotlinx.serialization.Serializable
private data class PrivatePaymentsEnvelope(
    val version: Int,
    val kind: String,
    val reference: String,
    val entries: Map<String, String>,
)

internal data class PrivatePaykitPayloadSelection(
    val entries: List<StoredPaymentEntry>,
    val droppedLightning: Boolean = false,
)
