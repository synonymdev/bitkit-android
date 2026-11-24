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
        assertEquals(BIP84_ACCOUNT_0_XPUB, coreAccountXpub(Network.BITCOIN, 0))
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

        // BIP84 test vector, m/84'/0'/0' with xpub version bytes.
        const val BIP84_ACCOUNT_0_XPUB =
            "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3Xyuv" +
                "PEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V"

        // m/84'/1'/1' with tpub version bytes, as ldk-node exports it on regtest; derived independently of bitkit-core.
        const val REGTEST_ACCOUNT_1_TPUB =
            "tpubDC8msFGeGuwnP2xwTZBBZSie1BLgRAkJhgzpFYTdpGgZNzguXQhNDVWp7mJb" +
                "HJUjQQvV2myLU9dkx67a7VAUnzY7yT7nvhHj7FgS4oNivvq"
    }
}
