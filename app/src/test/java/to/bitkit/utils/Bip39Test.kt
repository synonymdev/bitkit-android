package to.bitkit.utils

import junit.framework.TestCase.assertFalse
import org.junit.Assert
import org.junit.Test
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


    @Test
    fun `isBip39 should return true for valid BIP39 word`() {
        Assert.assertTrue("abandon".isBip39())
        Assert.assertTrue("zoo".isBip39())
        Assert.assertTrue("abandon".uppercase().isBip39())
        Assert.assertTrue("abandon".lowercase().isBip39())
        Assert.assertTrue("abandon".capitalize().isBip39())
    }

    @Test
    fun `isBip39 should return false for invalid BIP39 word`() {
        Assert.assertFalse("invalidword".isBip39())
        Assert.assertFalse("".isBip39())
        Assert.assertFalse("123".isBip39())
        Assert.assertFalse("abandon ".isBip39())
        Assert.assertFalse(" abandon".isBip39())
        Assert.assertFalse(" abandon ".isBip39())
        Assert.assertFalse("abandon1".isBip39())
        Assert.assertFalse("abandon-".isBip39())
        Assert.assertFalse("abandon_".isBip39())
    }

    @Test
    fun `isBip39 should handle empty string`() {
        Assert.assertFalse("".isBip39())
    }

    @Test
    fun `isBip39 should handle non-alphabetic characters`() {
        Assert.assertFalse("123".isBip39())
        Assert.assertFalse("!@#".isBip39())
        Assert.assertFalse("abandon1".isBip39())
        Assert.assertFalse("abandon-".isBip39())
        Assert.assertFalse("abandon_".isBip39())
    }

}
