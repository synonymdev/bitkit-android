package to.bitkit.wear.data

/**
 * The time since the watch booted, in milliseconds. Unlike the wall clock it cannot be set, and unlike
 * `System.nanoTime` it keeps counting while the watch sleeps, so it measures how old something is.
 */
fun interface ElapsedClock {
    fun nowMs(): Long
}
