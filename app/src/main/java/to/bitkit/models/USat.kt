package to.bitkit.models

/**
 * A wrapper for [ULong] that provides saturating arithmetic operations.
 * All operations prevent overflow/underflow by clamping to valid range [0, [ULong.MAX_VALUE]].
 * Similar to Rust's saturating arithmetic (e.g., `x.saturating_sub(y)`).
 */
@JvmInline
value class USat(val value: ULong) : Comparable<USat> {

    override fun compareTo(other: USat): Int = value.compareTo(other.value)

    /** Saturating subtraction: returns 0 if result would be negative. */
    operator fun minus(other: USat): ULong =
        if (value >= other.value) value - other.value else 0uL

    /** Saturating addition: caps at ULong.MAX_VALUE if result would overflow. */
    operator fun plus(other: USat): ULong =
        if (value <= ULong.MAX_VALUE - other.value) value + other.value else ULong.MAX_VALUE

    /** Saturating multiplication: caps at ULong.MAX_VALUE if result would overflow. */
    operator fun times(other: USat): ULong =
        if (other.value == 0uL || value <= ULong.MAX_VALUE / other.value) value * other.value else ULong.MAX_VALUE
}

/**
 * Wraps this ULong in a [USat] for saturating arithmetic operations.
 * Use this when performing arithmetic that could overflow/underflow.
 *
 * Example:
 * ```
 * val result = a.safe() - b.safe()  // Returns 0 if a < b instead of wrapping
 * ```
 */
fun ULong.safe(): USat = USat(this)
