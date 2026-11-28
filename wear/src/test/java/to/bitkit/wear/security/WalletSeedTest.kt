package to.bitkit.wear.security

import org.junit.Test
import kotlin.test.assertFalse

class WalletSeedTest {
    private val seed = WalletSeed(
        mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        passphrase = "TREZOR",
    )

    @Test
    fun `toString never prints the words or the passphrase`() {
        val printed = "$seed"

        assertFalse("abandon" in printed)
        assertFalse("TREZOR" in printed)
    }
}
