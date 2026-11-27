package to.bitkit.wear.security

import com.synonym.bitkitcore.getBip39Suggestions
import com.synonym.bitkitcore.isValidBip39Word
import com.synonym.bitkitcore.validateMnemonic
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The BIP39 operations the watch needs, over the English wordlist. Every BIP39 call in the watch app goes through
 * this interface, so the library behind it can be swapped in this file alone.
 */
interface Bip39 {
    companion object {
        /** Letters to type before [suggestions] offers words. */
        const val MIN_PREFIX_LENGTH = 2

        /** The longest word in the English wordlist. */
        const val MAX_WORD_LENGTH = 8

        /** [input] as typed on a watch keyboard, reduced to what a wordlist word can hold. */
        fun sanitize(input: String): String = input.lowercase().filter { it in 'a'..'z' }.take(MAX_WORD_LENGTH)
    }

    fun isWord(word: String): Boolean

    /** The wordlist words that start with [prefix], once it has [MIN_PREFIX_LENGTH] letters. */
    fun suggestions(prefix: String): List<String>

    /** Checks the words and the checksum of [words], which must already be lowercase. */
    fun validate(words: List<String>): Bip39Validation
}

sealed interface Bip39Validation {
    data object Valid : Bip39Validation

    /** The word at [index], counted from 0, is not in the wordlist. */
    data class UnknownWord(val index: Int) : Bip39Validation

    /** Every word is in the wordlist, but the phrase fails the checksum: a word, the order or the length is off. */
    data object InvalidChecksum : Bip39Validation
}

/** [Bip39] backed by bitkit-core, as on the phone. */
@Singleton
class CoreBip39 @Inject constructor() : Bip39 {
    companion object {
        /** More than any prefix of [Bip39.MIN_PREFIX_LENGTH] letters matches, so no suggestion is cut off. */
        private const val MAX_SUGGESTIONS = 2048u
    }

    override fun isWord(word: String): Boolean = isValidBip39Word(word)

    override fun suggestions(prefix: String): List<String> {
        if (prefix.length < Bip39.MIN_PREFIX_LENGTH) return emptyList()
        return getBip39Suggestions(prefix, MAX_SUGGESTIONS)
    }

    override fun validate(words: List<String>): Bip39Validation {
        val unknown = words.indexOfFirst { !isValidBip39Word(it) }
        if (unknown >= 0) return Bip39Validation.UnknownWord(unknown)
        return runCatching { validateMnemonic(words.joinToString(" ")) }
            .fold(onSuccess = { Bip39Validation.Valid }, onFailure = { Bip39Validation.InvalidChecksum })
    }
}
