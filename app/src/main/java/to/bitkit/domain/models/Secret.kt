package to.bitkit.domain.models

import kotlin.reflect.KProperty

private const val WIPE_CHAR = '\u0000'

/**
 * A property delegate that stores sensitive data in a [CharArray] and provides APIs to safely wipe it from memory.
 *
 * ALWAYS process the wrapped value through [use] blocks API for auto cleanup, as it implements [AutoCloseable].
 */
internal class Secret(initialValue: CharArray) : AutoCloseable {
    companion object {
        private const val ERR_WIPED = "Secret has already been wiped."
    }

    private var data: CharArray? = initialValue.copyOf()

    init {
        // Wipe the input array immediately after copying to reduce exposure
        wipe(nullify = false)
    }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): CharArray {
        return checkNotNull(data) { ERR_WIPED }
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: CharArray) {
        wipe(nullify = false)
        data = value.copyOf()
        // Wipe the source array
        value.fill(WIPE_CHAR)
    }

    /**
     * Temporarily access the underlying data, then automatically wipe it.
     *
     * ```
     * secret("myToken".toCharArray()).use { authenticate(it) }
     * // chars are wiped here
     * ```
     */
    inline fun <R> use(block: (CharArray) -> R): R {
        try {
            return block(checkNotNull(data) { ERR_WIPED })
        } finally {
            wipe()
        }
    }

    /**
     * Access the data without wiping afterwards.
     * Useful when you need multiple reads before an explicit [wipe].
     *
     * ```kotlin
     * mySecret.peek { chars -> hash(chars) }`
     * // data is still alive here
     * ```
     */
    inline fun <R> peek(block: (CharArray) -> R): R {
        return block(checkNotNull(data) { ERR_WIPED })
    }

    /**
     * Zero-out the backing memory and optionally nullify the reference by default.
     * Safe to call multiple times.
     */
    fun wipe(nullify: Boolean = true) {
        data?.fill(WIPE_CHAR)
        if (nullify) data = null
    }

    /** Alias for [wipe] to satisfy [AutoCloseable]. */
    override fun close() = wipe()
}

/** Create a [Secret] from a [CharArray]. The source array is wiped. */
internal fun secretOf(initialValue: CharArray) = Secret(initialValue)

/** Create a [Secret] from a [String]. The string can't be wiped from
 *  the JVM string pool, so prefer [CharArray] overloads where possible. */
internal fun secretOf(value: String): Secret {
    val chars = value.toCharArray()
    return Secret(chars) // constructor wipes `chars`
}

/**
 * Convert a [String] to a [Secret].
 *
 * ⚠️ The original [String] remains in the JVM string pool and cannot be wiped.
 * Prefer constructing from [CharArray] literals where possible.
 */
internal fun String.toSecret(): Secret = secretOf(this)

/**
 * Convert a [CharArray] to a [Secret]. The source array is wiped.
 */
internal fun CharArray.toSecret(): Secret = secretOf(this)

/**
 * Convert a [String] to a secure [CharArray], pass it to [block], then wipe it, all without storing a property.
 *
 * ```
 * "temporarySecret".withSecret { chars ->
 *     sendOverTls(chars)
 * }
 * ```
 */
internal inline fun <R> String.withSecret(block: (CharArray) -> R): R {
    val chars = toCharArray()
    try {
        return block(chars)
    } finally {
        chars.fill(WIPE_CHAR)
    }
}
