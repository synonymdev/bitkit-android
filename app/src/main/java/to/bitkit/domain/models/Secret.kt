package to.bitkit.domain.models

import kotlin.reflect.KProperty

private const val WIPE_CHAR = '\u0000'

/**
 * A wrapper that stores sensitive data in a [CharArray] and provides APIs to safely wipe it from memory.
 *
 * ALWAYS access the wrapped value inside [use] blocks for auto cleanup.
 */
class Secret internal constructor(initialValue: CharArray) : AutoCloseable {
    companion object {
        const val ERR_WIPED = "Secret has already been wiped."
    }

    @PublishedApi
    internal var data: CharArray? = initialValue.copyOf()

    init {
        initialValue.wipe()
    }

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

internal inline fun <R> Secret.useAsString(block: (String) -> R): R = use { block(String(it)) }

internal fun Secret.splitWords(): List<Secret> =
    peek { chars -> String(chars).split(" ").filter { it.isNotBlank() }.map { secretOf(it) } }

internal fun List<Secret>.wipeAll() = forEach { it.wipe() }
