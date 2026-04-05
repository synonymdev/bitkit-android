package to.bitkit.models

/**
 * A wrapper for millisatoshi [ULong] values that provides explicit sat conversions.
 *
 * Eliminates scattered `/ 1000u` and `(x + 999u) / 1000u` patterns by encoding
 * the rounding intent directly in the API: [ceil] rounds up, [floor] truncates.
 *
 * Zero runtime overhead — this is an [inline value class][JvmInline].
 */
@JvmInline
value class MSat(val value: ULong) {

    /** Round up to the nearest whole sat. Use for payment/display amounts. */
    fun ceil(): ULong = (value + 999u) / 1000u

    /** Truncate sub-sat remainder. Use for fees and upper bounds. */
    fun floor(): ULong = value / 1000u
}
