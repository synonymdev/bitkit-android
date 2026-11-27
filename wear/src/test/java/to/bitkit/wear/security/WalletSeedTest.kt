package to.bitkit.wear.security

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    @Test
    fun `the placeholder store holds no seed and refuses every call`() = runTest {
        val store = UnimplementedSeedStore()

        assertFalse(store.exists())
        assertIs<SeedStoreError.NotImplemented>(store.load().exceptionOrNull())
        assertTrue(store.save(seed).isFailure)
        assertTrue(store.wipe().isFailure)
    }
}
