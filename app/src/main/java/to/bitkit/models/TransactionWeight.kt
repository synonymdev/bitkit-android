package to.bitkit.models

import org.lightningdevkit.ldknode.TransactionDetails

private const val VERSION_AND_LOCK_TIME_BYTES = 8L
private const val OUTPOINT_AND_SEQUENCE_BYTES = 40L
private const val OUTPUT_VALUE_BYTES = 8L
private const val SEGWIT_MARKER_AND_FLAG_WU = 2L
private const val WITNESS_SCALE_FACTOR = 4L
private const val COMPACT_SIZE_ONE_BYTE_MAX = 0xFCL
private const val COMPACT_SIZE_THREE_BYTES_MAX = 0xFFFFL
private const val COMPACT_SIZE_FIVE_BYTES_MAX = 0xFFFF_FFFFL

/** The weight of this signed transaction as serialized, from its hex scripts and witness items. */
internal fun TransactionDetails.weight(): ULong {
    val inputBytes = inputs.sumOf { OUTPOINT_AND_SEQUENCE_BYTES + it.scriptsig.serializedSize() }
    val outputBytes = outputs.sumOf { OUTPUT_VALUE_BYTES + it.scriptpubkey.serializedSize() }
    val baseBytes = VERSION_AND_LOCK_TIME_BYTES + inputs.size.toLong().compactSize() + inputBytes +
        outputs.size.toLong().compactSize() + outputBytes
    if (inputs.all { it.witness.isEmpty() }) return (baseBytes * WITNESS_SCALE_FACTOR).toULong()

    val witnessBytes = inputs.sumOf { input ->
        input.witness.size.toLong().compactSize() + input.witness.sumOf { it.serializedSize() }
    }
    return (baseBytes * WITNESS_SCALE_FACTOR + SEGWIT_MARKER_AND_FLAG_WU + witnessBytes).toULong()
}

/** This signed transaction's virtual size, rounded up. */
internal fun TransactionDetails.vBytes(): ULong = divideRoundingUp(weight(), WITNESS_SCALE_FACTOR.toULong())

/** The rate that [feeSats] pays on this signed transaction, per virtual byte and rounded up. */
internal fun TransactionDetails.feeRate(feeSats: ULong): ULong = divideRoundingUp(feeSats, vBytes())

internal fun divideRoundingUp(dividend: ULong, divisor: ULong): ULong =
    (dividend.safe() + (divisor.safe() - 1uL.safe()).safe()) / divisor

private fun String.serializedSize(): Long = (length / 2).toLong().let { it.compactSize() + it }

private fun Long.compactSize(): Long = when {
    this <= COMPACT_SIZE_ONE_BYTE_MAX -> 1L
    this <= COMPACT_SIZE_THREE_BYTES_MAX -> 1L + Short.SIZE_BYTES
    this <= COMPACT_SIZE_FIVE_BYTES_MAX -> 1L + Int.SIZE_BYTES
    else -> 1L + Long.SIZE_BYTES
}
