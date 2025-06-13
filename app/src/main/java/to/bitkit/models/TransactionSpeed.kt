package to.bitkit.models

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import to.bitkit.R

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
            value == "fast" -> Fast
            value == "medium" -> Medium
            value == "slow" -> Slow
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

@Composable
fun TransactionSpeed.transactionSpeedUiText(): String {
    return when (this) {
        is TransactionSpeed.Fast -> stringResource(R.string.settings__fee__fast__value)
        is TransactionSpeed.Medium -> stringResource(R.string.settings__fee__normal__value)
        is TransactionSpeed.Slow -> stringResource(R.string.settings__fee__slow__value)
        is TransactionSpeed.Custom -> stringResource(R.string.settings__fee__custom__value)
    }
}
