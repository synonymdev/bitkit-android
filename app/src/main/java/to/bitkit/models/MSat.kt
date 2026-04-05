package to.bitkit.models

/**
 * A non-boxing wrapper for millisatoshi [ULong] values that provides explicit sat conversions.
 *
 * Encapsulates the rounding intent directly in the API: [ceil] rounds up, [floor] truncates.
 */
@JvmInline
value class MSat(val value: ULong) {

    companion object {
        const val PER_SAT: ULong = 1000u
    }

    /** Round up to the nearest whole sat. Use for payment/display amounts. */
    fun ceil(): ULong = (value + PER_SAT - 1u) / PER_SAT

    /** Truncate sub-sat remainder. Use for fees and upper bounds. */
    fun floor(): ULong = value / PER_SAT
}
