package to.bitkit.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.deriveOnchainDescriptor
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.Network
import to.bitkit.models.toCoreNetwork
import to.bitkit.test.BaseAndroidTest
import to.bitkit.test.annotations.CoreServiceIntegration
import to.bitkit.test.annotations.DeviceIntegration
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@DeviceIntegration
@CoreServiceIntegration
class WatchOnlyAccountXpubDerivationTest : BaseAndroidTest() {
    @Test
    fun coreDerivationMatchesTheMainnetAccountXpub() {
        assertEquals(MAINNET_ACCOUNT_1_XPUB, coreAccountXpub(Network.BITCOIN, 1))
    }

    @Test
    fun coreDerivationMatchesTheRegtestAccountXpubLdkNodeExports() {
        assertEquals(REGTEST_ACCOUNT_1_TPUB, coreAccountXpub(Network.REGTEST, 1))
    }

    private fun coreAccountXpub(network: Network, accountIndex: Int): String {
        val descriptor = deriveOnchainDescriptor(
            mnemonicPhrase = TEST_MNEMONIC,
            network = network.toCoreNetwork(),
            bip39Passphrase = null,
            accountType = AccountType.NATIVE_SEGWIT,
            accountIndex = accountIndex.toUInt(),
        )
        return accountXpubForNetwork(descriptorAccountXpub(descriptor), network)
    }

    private companion object {
        const val TEST_MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

        // m/84'/0'/1'; ldk-node only accepts watch-only accounts from index 1. Derived independently of bitkit-core.
        const val MAINNET_ACCOUNT_1_XPUB =
            "xpub6CatWdiZiodmYVtWLtEQsAg1H9ooS1bmsJUBwQ83FE1Fyk386FWcyic" +
                "JgEZv3quZSJKA5dh5Lo2PbubMGxCfZtRthV6ST2qquL9w3HSzcUn"

        // m/84'/1'/1' with tpub version bytes, as ldk-node exports it on regtest; derived independently of bitkit-core.
        const val REGTEST_ACCOUNT_1_TPUB =
            "tpubDC8msFGeGuwnP2xwTZBBZSie1BLgRAkJhgzpFYTdpGgZNzguXQhNDVWp7mJb" +
                "HJUjQQvV2myLU9dkx67a7VAUnzY7yT7nvhHj7FgS4oNivvq"
    }
}
