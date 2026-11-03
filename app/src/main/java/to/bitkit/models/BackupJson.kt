package to.bitkit.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import to.bitkit.data.SettingsData
import to.bitkit.di.json
import to.bitkit.utils.Logger

/**
 * Reads backup envelopes written by either app. Decode-only: backups are still written with the app's
 * `json`, so older Android readers keep decoding what this app uploads.
 *
 * bitkit-ios encodes Core types with Swift's synthesized `Codable`, which differs from kotlinx in three ways:
 * it leaves nil optionals out, writes an enum as `{"case":{}}`, and writes an enum with a value as
 * `{"case":{"_0":value}}`. This reader accepts missing nullable fields, and [fromSwiftCodable] rewrites the
 * other two into kotlinx's shape, so envelopes this app wrote pass through unchanged.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val backupJson = Json(json) {
    explicitNulls = false
    coerceInputValues = true
}

internal inline fun <reified T> decodeBackup(raw: String): T = decodeBackup(serializer<T>(), raw)

internal fun <T> decodeBackup(serializer: KSerializer<T>, raw: String): T {
    val element = backupJson.parseToJsonElement(raw).fromSwiftCodable(serializer.descriptor)
    return backupJson.decodeFromJsonElement(serializer, element)
}

/**
 * Decodes a SETTINGS envelope one setting at a time, so a value this app cannot read keeps its default
 * instead of failing the whole category. bitkit-ios writes some settings in its own form, such as
 * `addressTypesToMonitor` as a comma-separated string.
 */
internal fun decodeSettingsBackup(raw: String): SettingsBackupV1 {
    val root = backupJson.parseToJsonElement(raw).jsonObject
    val settings = requireNotNull(root["settings"] as? JsonObject) { "Settings backup has no settings object" }
    val serializer = SettingsData.serializer()
    val readable = settings.entries.fold(JsonObject(emptyMap())) { kept, (key, value) ->
        val candidate = JsonObject(kept + (key to value.asAndroidSetting(key)))
        val decodes = runCatching {
            backupJson.decodeFromJsonElement(serializer, candidate.fromSwiftCodable(serializer.descriptor))
        }
        if (decodes.isSuccess) {
            candidate
        } else {
            Logger.warn("Skipped unreadable backup setting '$key'", decodes.exceptionOrNull(), context = TAG)
            kept
        }
    }
    val envelope = JsonObject(root + ("settings" to readable))
    return decodeBackup(SettingsBackupV1.serializer(), envelope.toString())
}

private fun JsonElement.asAndroidSetting(key: String): JsonElement {
    if (key != "addressTypesToMonitor" || this !is JsonPrimitive || !isString) return this
    return JsonArray(content.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::JsonPrimitive))
}

/** Rewrites Swift `Codable` enum shapes into kotlinx's, guided by the target [descriptor]. */
@OptIn(ExperimentalSerializationApi::class)
internal fun JsonElement.fromSwiftCodable(descriptor: SerialDescriptor): JsonElement = when (descriptor.kind) {
    SerialKind.ENUM -> swiftEnumCase()?.let(descriptor::enumNameMatching)?.let(::JsonPrimitive) ?: this
    PolymorphicKind.SEALED -> (this as? JsonObject)?.fromSwiftSealed(descriptor) ?: this
    StructureKind.CLASS -> (this as? JsonObject)?.fromSwiftFields(descriptor) ?: this
    StructureKind.LIST -> (this as? JsonArray)?.fromSwiftItems(descriptor.getElementDescriptor(0)) ?: this
    StructureKind.MAP -> (this as? JsonObject)?.fromSwiftValues(descriptor.getElementDescriptor(1)) ?: this
    else -> this
}

private fun JsonArray.fromSwiftItems(item: SerialDescriptor) = JsonArray(map { it.fromSwiftCodable(item) })

private fun JsonObject.fromSwiftValues(value: SerialDescriptor) = JsonObject(
    mapValues { (_, it) -> it.fromSwiftCodable(value) }
)

private fun JsonObject.fromSwiftFields(descriptor: SerialDescriptor) = JsonObject(
    mapValues { (key, value) ->
        val index = descriptor.getElementIndex(key)
        if (index < 0) value else value.fromSwiftCodable(descriptor.getElementDescriptor(index))
    }
)

private fun JsonObject.fromSwiftSealed(descriptor: SerialDescriptor): JsonObject {
    val discriminator = backupJson.configuration.classDiscriminator
    val subclasses = descriptor.getElementDescriptor(1).elementDescriptors
    this[discriminator]?.jsonPrimitive?.contentOrNull?.let { type ->
        val subclass = subclasses.firstOrNull { it.serialName == type } ?: return this
        return JsonObject(fromSwiftFields(subclass) + (discriminator to JsonPrimitive(type)))
    }
    val (case, payload) = entries.singleOrNull() ?: return this
    val subclass = subclasses.firstOrNull { it.serialName.substringAfterLast('.').matches(case) } ?: return this
    val values = payload as? JsonObject ?: return this
    val fields = values.mapKeys { (label, _) ->
        label.removePrefix("_").toIntOrNull()?.let { subclass.getElementName(it) } ?: label
    }
    return JsonObject(
        JsonObject(fields).fromSwiftFields(subclass) + (discriminator to JsonPrimitive(subclass.serialName))
    )
}

private fun JsonElement.swiftEnumCase(): String? = when {
    this is JsonPrimitive && isString -> content
    this is JsonObject && size == 1 && (values.single() as? JsonObject)?.isEmpty() == true -> keys.single()
    else -> null
}

@OptIn(ExperimentalSerializationApi::class)
private fun SerialDescriptor.enumNameMatching(case: String): String? = elementNames.firstOrNull { it.matches(case) }

private fun String.matches(other: String) = normalized() == other.normalized()

private fun String.normalized() = filter { it != '_' }.lowercase()

private const val TAG = "BackupJson"
