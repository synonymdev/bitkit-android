package to.bitkit.wear.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import to.bitkit.wear.utils.AppError

/**
 * The phone's QuickPay settings: payments of up to [amountUsd] US dollars skip the confirmation, up to
 * [amountUsd] times [dailyMultiplier] dollars a day.
 */
data class QuickPayLimits(
    val isEnabled: Boolean,
    val amountUsd: Int,
    val dailyMultiplier: Int,
) {
    val thresholdCents: Long get() = amountUsd * CENTS_PER_USD
    val dailyCapCents: Long get() = thresholdCents * dailyMultiplier

    companion object {
        private const val CENTS_PER_USD = 100L

        /** The largest values the phone's QuickPay sliders offer; a backup with more is not the phone's. */
        const val MAX_AMOUNT_USD = 50
        const val MAX_DAILY_MULTIPLIER = 50
    }
}

/**
 * What the watch adopts from the phone's settings backup. A display value is null when the backup does not hold a
 * readable one, and the watch then keeps its own. [quickPay] is null when its values cannot all be read, which turns
 * QuickPay off on the watch.
 */
data class PhoneSettings(
    val primaryDisplay: PrimaryDisplay?,
    val displayUnit: BitcoinDisplayUnit?,
    val selectedCurrency: String?,
    val quickPay: QuickPayLimits?,
)

sealed class PhoneSettingsError(message: String, cause: Throwable? = null) : AppError(message, cause) {
    /** The backup is not the JSON envelope the phone writes. */
    class Unreadable(cause: Throwable? = null) : PhoneSettingsError("The settings backup cannot be read", cause)

    /** The backup was written in a layout newer than this app knows. */
    class NewerSchema(version: Int) : PhoneSettingsError("The settings backup has version '$version'")
}

/**
 * Reads the phone's `BackupCategory.SETTINGS` envelope (`SettingsBackupV1`: `version`, `createdAt`, `settings`), as
 * bitkit-android and bitkit-ios write it. Only the keys the watch adopts are read; every other key is ignored.
 */
object PhoneSettingsParser {
    /** The newest envelope version this reader knows. */
    const val SCHEMA_VERSION = 1

    /** A settings envelope is a few kilobytes; anything larger than this is not one. */
    const val MAX_BYTES = 256 * 1024

    private val json = Json { isLenient = true }

    /** Fails with a [PhoneSettingsError]. */
    fun parse(bytes: ByteArray): PhoneSettings {
        if (bytes.size > MAX_BYTES) throw PhoneSettingsError.Unreadable()
        val root = runCatching { json.parseToJsonElement(bytes.decodeToString()) as JsonObject }
            .getOrElse { throw PhoneSettingsError.Unreadable(it) }
        val version = when (val element = root["version"]) {
            null -> SCHEMA_VERSION
            else -> (element as? JsonPrimitive)?.intOrNull ?: throw PhoneSettingsError.Unreadable()
        }
        if (version > SCHEMA_VERSION) throw PhoneSettingsError.NewerSchema(version)
        if (version < 1) throw PhoneSettingsError.Unreadable()
        val settings = root["settings"] as? JsonObject ?: throw PhoneSettingsError.Unreadable()
        return PhoneSettings(
            primaryDisplay = settings["primaryDisplay"].enumOrNull<PrimaryDisplay>(),
            displayUnit = settings["displayUnit"].enumOrNull<BitcoinDisplayUnit>(),
            selectedCurrency = settings["selectedCurrency"].stringOrNull()?.takeIf { CURRENCY_CODE.matches(it) },
            quickPay = quickPay(settings),
        )
    }

    private fun quickPay(settings: JsonObject): QuickPayLimits? {
        val isEnabled = (settings["isQuickPayEnabled"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val amountUsd = settings["quickPayAmount"].intOrNull()
        val multiplier = settings["quickPayDailyLimitMultiplier"].intOrNull()
        if (isEnabled == null || amountUsd == null || multiplier == null) return null
        if (amountUsd !in 1..QuickPayLimits.MAX_AMOUNT_USD) return null
        if (multiplier !in 1..QuickPayLimits.MAX_DAILY_MULTIPLIER) return null
        return QuickPayLimits(isEnabled, amountUsd, multiplier)
    }

    private fun JsonElement?.intOrNull(): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * An enum as kotlinx writes it, a string, or as Swift's `Codable` does, an object with the case as its one key.
     * Names match without their case and underscores, as in the phone's reader.
     */
    private inline fun <reified T : Enum<T>> JsonElement?.enumOrNull(): T? {
        val name = when (this) {
            is JsonPrimitive -> stringOrNull()
            is JsonObject -> keys.singleOrNull()
            else -> null
        } ?: return null
        return enumValues<T>().firstOrNull { it.name.normalized() == name.normalized() }
    }

    private fun String.normalized() = filter { it != '_' }.lowercase()

    private val CURRENCY_CODE = Regex("^[A-Z]{3}$")
}
