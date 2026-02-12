package to.bitkit.domain.models

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SecretTest {
    @Test
    fun `length returns correct size`() {
        val secret = secretOf("hello")
        assertEquals(5, secret.length)
    }

    @Test
    fun `length returns 0 after wipe`() {
        val secret = secretOf("hello")
        secret.wipe()
        assertEquals(0, secret.length)
    }

    @Test
    fun `get returns correct character`() {
        val secret = secretOf("hello")
        assertEquals('h', secret[0])
        assertEquals('o', secret[4])
    }

    @Test
    fun `get throws after wipe`() {
        val secret = secretOf("hello")
        secret.wipe()
        assertFailsWith<IllegalStateException> { secret[0] }
    }

    @Test
    fun `subSequence returns correct subsequence`() {
        val secret = secretOf("hello world")
        val sub = secret.subSequence(0, 5) as Secret
        sub.peek { assertEquals("hello", String(it)) }
    }

    @Test
    fun `subSequence throws after wipe`() {
        val secret = secretOf("hello")
        secret.wipe()
        assertFailsWith<IllegalStateException> { secret.subSequence(0, 3) }
    }

    @Test
    fun `toString returns redacted value`() {
        val secret = secretOf("sensitive data")
        assertEquals("Secret(***)", secret.toString())
        assertNotEquals("sensitive data", secret.toString())
    }

    @Test
    fun `toString returns redacted value after wipe`() {
        val secret = secretOf("sensitive data")
        secret.wipe()
        assertEquals("Secret(***)", secret.toString())
    }

    @Test
    fun `splitWords splits by spaces without creating String`() {
        val secret = secretOf("abandon ability able about")
        val words = secret.splitWords()
        assertEquals(4, words.size)
        words[0].peek { assertEquals("abandon", String(it)) }
        words[1].peek { assertEquals("ability", String(it)) }
        words[2].peek { assertEquals("able", String(it)) }
        words[3].peek { assertEquals("about", String(it)) }
        words.wipeAll()
    }

    @Test
    fun `splitWords handles multiple spaces`() {
        val secret = secretOf("word1  word2")
        val words = secret.splitWords()
        assertEquals(2, words.size)
        words[0].peek { assertEquals("word1", String(it)) }
        words[1].peek { assertEquals("word2", String(it)) }
        words.wipeAll()
    }

    @Test
    fun `splitWords handles single word`() {
        val secret = secretOf("singleword")
        val words = secret.splitWords()
        assertEquals(1, words.size)
        words[0].peek { assertEquals("singleword", String(it)) }
        words.wipeAll()
    }

    @Test
    fun `splitWords handles 12-word mnemonic`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about"
        val secret = secretOf(mnemonic)
        val words = secret.splitWords()
        assertEquals(12, words.size)
        words[0].peek { assertEquals("abandon", String(it)) }
        words[11].peek { assertEquals("about", String(it)) }
        words.wipeAll()
    }

    @Test
    fun `wipe clears underlying data`() {
        val secret = secretOf("secret")
        secret.wipe()
        assertFailsWith<IllegalStateException> { secret.peek { it } }
    }

    @Test
    fun `use auto-wipes after block`() {
        val secret = secretOf("secret")
        secret.use { assertEquals("secret", String(it)) }
        assertFailsWith<IllegalStateException> { secret.peek { it } }
    }

    @Test
    fun `CharSequence interface works with standard library`() {
        val secret = secretOf("hello")
        val cs: CharSequence = secret
        assertEquals(5, cs.length)
        assertEquals('h', cs[0])
        assertEquals('e', cs[1])
    }
}
