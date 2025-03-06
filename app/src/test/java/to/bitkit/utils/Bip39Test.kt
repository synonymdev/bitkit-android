package to.bitkit.utils

import junit.framework.TestCase.assertFalse
import org.junit.Before
import org.junit.Test
import to.bitkit.env.Env.DERIVATION_NAME
import to.bitkit.ext.fromBase64
import to.bitkit.ext.fromHex
import to.bitkit.ext.toHex
import to.bitkit.ext.toBase64
import to.bitkit.fcm.EncryptedNotification
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Bip39Test {
    private fun String.toWordList(): List<String> = this.trim().lowercase().split(" ")

    @Test
    fun `test valid mnemonic phrases`() {
        // Test vectors based on Trezor's reference implementation
        // From https://github.com/trezor/python-mnemonic/blob/master/vectors.json
        val testVectors = listOf(
            // 12 words (128 bits entropy)
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about" to true,
            "legal winner thank year wave sausage worth useful legal winner thank yellow" to true,
            "letter advice cage absurd amount doctor acoustic avoid letter advice cage above" to true,
            "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong" to true,

            // 24 words (256 bits entropy)
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art" to true,
            "legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth title" to true,
            "letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic bless" to true,
            "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote" to true,
            "jelly better achieve collect unaware mountain thought cargo oxygen act hood bridge" to true,
            "dignity pass list indicate nasty swamp pool script soccer toe leaf photo multiply desk host tomato cradle drill spread actor shine dismiss champion exotic" to true,

            // Edge cases
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon" to false, // Invalid checksum
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon" to false, // Too few words
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon" to false // Invalid length
        )

        for ((mnemonic, expectedResult) in testVectors) {
            assertEquals(expectedResult, mnemonic.toWordList().validBip39Checksum(), "Failed for mnemonic: $mnemonic")
        }
    }

    @Test
    fun `test invalid word count`() {
        // 11 words (too few)
        assertFalse(listOf("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon").validBip39Checksum())

        // 13 words (invalid length)
        assertFalse(listOf("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon").validBip39Checksum())

        // 23 words (invalid length)
        assertFalse(listOf("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon").validBip39Checksum())
    }

    @Test
    fun `test invalid words`() {
        // Contains a word not in the wordlist
        assertFalse(listOf("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon invalidword").validBip39Checksum())
    }

    @Test
    fun `test invalid checksum`() {
        // Valid words but invalid checksum
        assertFalse(listOf("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon").validBip39Checksum())
    }

    @Test
    fun `test case sensitivity`() {
        // Original valid mnemonic
        val validMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

        // Test with uppercase
        assertTrue(validMnemonic.uppercase().toWordList().validBip39Checksum())

        // Test with mixed case
        assertTrue("AbAnDoN abandon ABANDON abandon abandon abandon abandon abandon abandon abandon abandon about".toWordList().validBip39Checksum())
    }

    @Test
    fun `test invalid examples with correct word count`() {
        assertFalse(listOf("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon").validBip39Checksum())
        assertFalse(listOf("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon zoo").validBip39Checksum())
        assertFalse(listOf("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon actor").validBip39Checksum())
    }

}
