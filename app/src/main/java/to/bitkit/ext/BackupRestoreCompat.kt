package to.bitkit.ext

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import to.bitkit.di.json
import to.bitkit.models.ActivityBackupV1
import to.bitkit.models.MetadataBackupV1
import to.bitkit.models.WalletScope

fun String.decodeActivityBackupV1Compat(): ActivityBackupV1 =
    json.decodeFromString(normalizeLegacyWalletIdsInBackupJson(this))

fun String.decodeMetadataBackupV1Compat(): MetadataBackupV1 =
    json.decodeFromString(normalizeLegacyWalletIdsInBackupJson(this))

private fun normalizeLegacyWalletIdsInBackupJson(
    raw: String,
    walletId: String = WalletScope.default,
): String {
    val normalized = json.parseToJsonElement(raw).normalizeLegacyWalletIds(walletId)
    return json.encodeToString(normalized)
}

private fun JsonElement.normalizeLegacyWalletIds(walletId: String): JsonElement = when (this) {
    is JsonObject -> {
        val patched = if (needsLegacyWalletId() && "walletId" !in this) {
            this + ("walletId" to JsonPrimitive(walletId))
        } else {
            this
        }
        JsonObject(patched.mapValues { (_, value) -> value.normalizeLegacyWalletIds(walletId) })
    }
    is JsonArray -> JsonArray(map { it.normalizeLegacyWalletIds(walletId) })
    else -> this
}

private fun JsonObject.needsLegacyWalletId(): Boolean = when {
    "paymentId" in this && "tags" in this && "isReceive" in this -> true
    "activityId" in this && "tags" in this && "paymentId" !in this -> true
    "txId" in this && "txType" in this && "value" in this -> true
    "invoice" in this && "status" in this && "txType" in this -> true
    else -> false
}
