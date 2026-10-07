package to.bitkit.models

import com.synonym.bitkitcore.usdtParsePaymentRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import to.bitkit.repositories.Endpoint
import to.bitkit.repositories.MethodId
import to.bitkit.repositories.PublicPaykitError

object PaykitUsdt {
    /** Arbitrum One's chain identifier. */
    const val CHAIN_ID = "42161"

    /** USDT0's pinned Arbitrum token contract. */
    const val TOKEN = "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9"

    private const val ADDRESS_LENGTH = 42

    fun address(payload: String): String? = runCatching {
        val fields = Json.parseToJsonElement(payload).jsonObject
        val hasStringFields = listOf("chain_id", "token", "value").all { fields[it]?.jsonPrimitive?.isString == true }
        if (!hasStringFields) return@runCatching null
        if (fields["chain_id"]?.jsonPrimitive?.contentOrNull != CHAIN_ID ||
            fields["token"]?.jsonPrimitive?.contentOrNull?.lowercase() != TOKEN
        ) {
            return@runCatching null
        }
        val value = fields["value"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        if (value.length != ADDRESS_LENGTH) return@runCatching null
        usdtParsePaymentRequest(value).takeIf { it.chainId == null && it.amount == null }?.recipient
    }.getOrNull()

    fun endpoint(address: String): Endpoint {
        val payload = JsonObject(
            mapOf(
                "value" to JsonPrimitive(address),
                "chain_id" to JsonPrimitive(CHAIN_ID),
                "token" to JsonPrimitive(TOKEN)
            )
        )
            .toString()
        return Endpoint(
            methodId = MethodId.UsdtArbitrum,
            value = address(payload) ?: throw PublicPaykitError.InvalidPayload,
            rawPayload = payload,
        )
    }

    fun paymentUri(address: String): String = "ethereum:$TOKEN@$CHAIN_ID/transfer?address=$address"
}
