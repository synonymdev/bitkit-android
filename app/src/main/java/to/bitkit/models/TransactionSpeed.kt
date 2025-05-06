package to.bitkit.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = TransactionSpeedSerializer::class)
sealed class TransactionSpeed {
    object Fast : TransactionSpeed()
    object Medium : TransactionSpeed()
    object Slow : TransactionSpeed()
    data class Custom(val satsPerVByte: UInt) : TransactionSpeed()

    fun serialized(): String = when (this) {
        is Fast -> "fast"
        is Medium -> "medium"
        is Slow -> "slow"
        is Custom -> "custom_$satsPerVByte"
    }

    companion object {
        fun fromString(value: String): TransactionSpeed = when {
            value.equals("fast", ignoreCase = true) -> Fast
            value.equals("medium", ignoreCase = true) -> Medium
            value.equals("slow", ignoreCase = true) -> Slow
            value.matches(Regex("custom_\\d+")) -> {
                value.substringAfter("custom_")
                    .toUIntOrNull()
                    ?.let { Custom(it) }
                    ?: Medium
            }

            else -> Medium
        }
    }
}

private object TransactionSpeedSerializer : KSerializer<TransactionSpeed> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("TransactionSpeed", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: TransactionSpeed) {
        encoder.encodeString(value.serialized())
    }

    override fun deserialize(decoder: Decoder): TransactionSpeed {
        return TransactionSpeed.fromString(decoder.decodeString())
    }
}
