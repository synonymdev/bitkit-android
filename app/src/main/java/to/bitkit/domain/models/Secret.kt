@file:Suppress("TooManyFunctions")

package to.bitkit.domain.models

import kotlin.reflect.KProperty

private const val WIPE_CHAR = '\u0000'

/**
 * A property delegate that stores sensitive data in a [CharArray] and provides APIs to safely wipe it from memory.
 *
 * ALWAYS process the wrapped value through [use] blocks API for auto cleanup, as it implements [AutoCloseable].
 */
class Secret internal constructor(initialValue: CharArray) : AutoCloseable {
    companion object {
        @PublishedApi
        internal const val ERR_WIPED = "Secret has already been wiped."
    }

    @PublishedApi
    internal var data: CharArray? = initialValue.copyOf()

    init {
        // Wipe the input array immediately after copying to reduce exposure
        initialValue.fill(WIPE_CHAR)
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
fun secretOf(initialValue: CharArray) = Secret(initialValue)

/** Create a [Secret] from a [String]. The string can't be wiped from
 *  the JVM string pool, so prefer [CharArray] overloads where possible. */
fun secretOf(value: String): Secret {
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

/** Nullable factory for Keychain integration - creates a [Secret] only if value is non-null. */
fun secretOrNull(value: String?): Secret? = value?.let { secretOf(it) }

/** Create a [Secret] from a [CharArray]. Alias for [secretOf]. */
fun secret(chars: CharArray): Secret = secretOf(chars)

/** Create a [Secret] from a [String]. Alias for [secretOf]. */
fun secret(value: String): Secret = secretOf(value)

/**
 * Convert the [Secret] to a [String] for FFI boundary, execute the block, then wipe.
 *
 * ⚠️ Use only at FFI boundaries where RUST requires String params.
 * The temporary String cannot be wiped from JVM memory.
 */
inline fun <R> Secret.useAsString(block: (String) -> R): R =
    use { chars -> block(String(chars)) }

/**
 * Split a mnemonic [Secret] into individual [Secret] words.
 * The original secret is NOT wiped - caller is responsible for calling [wipe] after use.
 */
fun Secret.splitWords(): List<Secret> =
    peek { chars -> String(chars).split(" ").filter { it.isNotBlank() }.map { secretOf(it) } }

/** Wipe all [Secret] instances in a list. Safe to call multiple times. */
fun List<Secret>.wipeAll() = forEach { it.wipe() }

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

/**
 * Execute a block with the string value, then wipe an internal [CharArray] representation.
 * This is useful when you need to pass a String to an API but want to minimize exposure.
 *
 * ⚠️ The original [String] cannot be wiped from JVM memory. This helps with
 * intermediate CharArray representations.
 *
 * ```
 * userInput.withSecretChars { str ->
 *     keychain.saveString(key, str)
 * }
 * ```
 */
internal inline fun <R> String.withSecretChars(block: (String) -> R): R {
    val chars = toCharArray()
    try {
        return block(this)
    } finally {
        chars.fill(WIPE_CHAR)
    }
}
