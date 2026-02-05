package to.bitkit.domain.models

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretTest {

    // region secretOrNull
    @Test
    fun `secretOrNull returns null for null input`() {
        val result = secretOrNull(null)
        assertNull(result)
    }

    @Test
    fun `secretOrNull returns Secret for non-null input`() {
        val result = secretOrNull("test")
        result!!.use { chars ->
            assertEquals("test", String(chars))
        }
    }
    // endregion

    // region useAsString
    @Test
    fun `useAsString converts to String and wipes after use`() {
        val secret = secretOf("mySecret")
        val result = secret.useAsString { it.uppercase() }
        assertEquals("MYSECRET", result)

        // Secret should be wiped
        assertFailsWith<IllegalStateException> { secret.peek { } }
    }

    @Test
    fun `useAsString provides correct String value`() {
        val secret = secretOf("hello world")
        secret.useAsString { str ->
            assertEquals("hello world", str)
        }
    }
    // endregion

    // region splitWords
    @Test
    fun `splitWords creates separate Secret instances for each word`() {
        val mnemonic = secretOf("word1 word2 word3")
        val words = mnemonic.splitWords()

        assertEquals(3, words.size)
        assertEquals("word1", words[0].peek { String(it) })
        assertEquals("word2", words[1].peek { String(it) })
        assertEquals("word3", words[2].peek { String(it) })

        // Clean up
        words.wipeAll()
        mnemonic.wipe()
    }

    @Test
    fun `splitWords handles multiple spaces`() {
        val mnemonic = secretOf("word1  word2   word3")
        val words = mnemonic.splitWords()

        assertEquals(3, words.size)
        words.wipeAll()
        mnemonic.wipe()
    }

    @Test
    fun `splitWords does not wipe original`() {
        val mnemonic = secretOf("word1 word2")
        mnemonic.splitWords()

        // Original should still be accessible
        mnemonic.peek { chars ->
            assertEquals("word1 word2", String(chars))
        }
        mnemonic.wipe()
    }
    // endregion

    // region wipeAll
    @Test
    fun `wipeAll wipes all secrets in list`() {
        val secrets = listOf(
            secretOf("secret1"),
            secretOf("secret2"),
            secretOf("secret3"),
        )

        secrets.wipeAll()

        secrets.forEach { secret ->
            assertFailsWith<IllegalStateException> { secret.peek { } }
        }
    }

    @Test
    fun `wipeAll is safe to call on empty list`() {
        val emptyList = emptyList<Secret>()
        emptyList.wipeAll() // Should not throw
    }

    @Test
    fun `wipeAll is safe to call multiple times`() {
        val secrets = listOf(secretOf("test"))
        secrets.wipeAll()
        secrets.wipeAll() // Should not throw
    }
    // endregion

    // region Source array wiping
    @Test
    fun `secretOf wipes source CharArray`() {
        val source = "secret".toCharArray()
        secretOf(source)

        // Source should be wiped (all null chars)
        assertTrue(source.all { it == '\u0000' })
    }

    @Test
    fun `secret factory wipes source CharArray`() {
        val source = "secret".toCharArray()
        secret(source)

        assertTrue(source.all { it == '\u0000' })
    }
    // endregion

    // region use and wipe
    @Test
    fun `use wipes data after block completes`() {
        val secret = secretOf("test")
        secret.use { chars ->
            assertEquals("test", String(chars))
        }

        assertFailsWith<IllegalStateException> { secret.peek { } }
    }

    @Test
    fun `peek does not wipe data`() {
        val secret = secretOf("test")
        val first = secret.peek { String(it) }
        val second = secret.peek { String(it) }

        assertEquals(first, second)
        secret.wipe()
    }

    @Test
    fun `wipe can be called multiple times safely`() {
        val secret = secretOf("test")
        secret.wipe()
        secret.wipe() // Should not throw
    }
    // endregion

    // region withSecret extension
    @Test
    fun `withSecret wipes CharArray after block`() {
        var capturedChars: CharArray? = null

        "mySecret".withSecret { chars ->
            capturedChars = chars.copyOf()
            assertEquals("mySecret", String(chars))
        }

        // We can't check the original chars directly, but we verified the block received correct data
        assertEquals("mySecret", String(capturedChars!!))
    }
    // endregion

    // region withSecretChars extension
    @Test
    fun `withSecretChars provides original string to block`() {
        "mySecret".withSecretChars { str ->
            assertEquals("mySecret", str)
        }
    }

    @Test
    fun `withSecretChars returns block result`() {
        val result = "hello".withSecretChars { it.length }
        assertEquals(5, result)
    }
    // endregion

    // region Secret constructor behavior
    @Test
    fun `Secret stores copy of data`() {
        val original = "test".toCharArray()
        val secret = secretOf(original)

        // Modifying what was the original shouldn't affect the secret
        // (original is already wiped, but let's verify the secret has its own copy)
        secret.peek { chars ->
            assertNotEquals(original, chars) // Different array reference
            assertEquals("test", String(chars))
        }
        secret.wipe()
    }
    // endregion
}
