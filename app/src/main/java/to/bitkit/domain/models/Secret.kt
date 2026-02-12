package to.bitkit.domain.models

import kotlin.reflect.KProperty

private const val WIPE_CHAR = '\u0000'

/**
 * A wrapper that stores sensitive data in a [CharArray] and provides APIs to safely wipe it from memory.
 *
 * Implements [CharSequence] so it can flow through internal APIs without materializing a [String].
 * [toString] is intentionally redacted — use [peek] or [use] to access the underlying data.
 *
 * ALWAYS access the wrapped value inside [use] blocks for auto cleanup.
 */
class Secret internal constructor(initialValue: CharArray) : CharSequence, AutoCloseable {
    companion object {
        const val ERR_WIPED = "Secret has already been wiped."
    }

    @PublishedApi
    internal var data: CharArray? = initialValue.copyOf()

    init {
        initialValue.wipe()
    }

    override val length: Int get() = data?.size ?: 0

    override fun get(index: Int): Char = checkNotNull(data) { ERR_WIPED }[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        secretOf(checkNotNull(data) { ERR_WIPED }.copyOfRange(startIndex, endIndex))

    override fun toString(): String = "Secret(***)"

    internal operator fun getValue(thisRef: Any?, property: KProperty<*>): CharArray {
        return checkNotNull(data) { ERR_WIPED }
    }

    internal operator fun setValue(thisRef: Any?, property: KProperty<*>, value: CharArray) {
        wipe(nullify = false)
        data = value.copyOf()
        value.wipe()
    }

    inline fun <R> use(block: (CharArray) -> R): R {
        try {
            return block(checkNotNull(data) { ERR_WIPED })
        } finally {
            wipe()
        }
    }

    inline fun <R> peek(block: (CharArray) -> R): R = block(checkNotNull(data) { ERR_WIPED })

    fun wipe(nullify: Boolean = true) {
        data?.wipe()
        if (nullify) data = null
    }

    fun CharArray.wipe() = this.fill(WIPE_CHAR)

    override fun close() = wipe()
}

fun secretOf(value: CharArray) = Secret(value)

fun secretOf(value: String): Secret = Secret(value.toCharArray())

internal fun Secret.splitWords(): List<Secret> = peek { chars ->
    val words = mutableListOf<Secret>()
    var start = 0
    for (i in chars.indices) {
        if (chars[i] == ' ') {
            if (i > start) words.add(secretOf(chars.copyOfRange(start, i)))
            start = i + 1
        }
    }
    if (start < chars.size) words.add(secretOf(chars.copyOfRange(start, chars.size)))
    words
}

internal fun List<Secret>.wipeAll() = forEach { it.wipe() }
