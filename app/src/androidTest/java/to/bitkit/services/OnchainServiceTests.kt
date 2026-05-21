package to.bitkit.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.AddressType
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.Network
import to.bitkit.models.toDerivationPath
import to.bitkit.test.annotations.CoreServiceIntegration
import to.bitkit.test.annotations.DeviceIntegration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@DeviceIntegration
@CoreServiceIntegration
class OnchainServiceTests {
    private lateinit var onchainService: OnchainService

    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Before
    fun setUp() {
        onchainService = OnchainService()
    }

    @Test
    fun testGenerateMnemonic(): Unit = runBlocking {
        val result = onchainService.generateMnemonic()

        assertNotNull(result)
        val words = result.split(" ")
        assertEquals(12, words.size)
    }

    @Test
    fun testDeriveAddressNativeSegWit(): Unit = runBlocking {
            val derivationPath = AddressType.P2WPKH.toDerivationPath(network = Network.BITCOIN)

            val result = onchainService.deriveBitcoinAddress(
                mnemonicPhrase = mnemonic,
                derivationPathStr = derivationPath,
                network = Network.BITCOIN,
                bip39Passphrase = null,
            )

            assertNotNull(result)
            assertNotNull(result.address)
            assertEquals(derivationPath, result.path)
            assertTrue(result.address.startsWith("bc1"), "Native SegWit address should start with bc1")
            assertNotNull(result.publicKey)
    }

    @Test
    fun testDeriveAddressSegWit(): Unit = runBlocking {
        val derivationPath = AddressType.P2SH.toDerivationPath(network = Network.BITCOIN)

        val result = onchainService.deriveBitcoinAddress(
            mnemonicPhrase = mnemonic,
            derivationPathStr = derivationPath,
            network = Network.BITCOIN,
            bip39Passphrase = null,
        )

        assertNotNull(result)
        assertNotNull(result.address)
        assertEquals(derivationPath, result.path)
        assertTrue(result.address.startsWith("3"), "SegWit address should start with 3")
        assertNotNull(result.publicKey)
    }

    @Test
    fun testDeriveAddressTaproot(): Unit = runBlocking {
        val derivationPath = AddressType.P2TR.toDerivationPath(network = Network.BITCOIN)

        val result = onchainService.deriveBitcoinAddress(
            mnemonicPhrase = mnemonic,
            derivationPathStr = derivationPath,
            network = Network.BITCOIN,
            bip39Passphrase = null,
        )

        assertNotNull(result)
        assertNotNull(result.address)
        assertEquals(derivationPath, result.path)
        assertTrue(result.address.startsWith("bc1p"), "Taproot address should start with bc1p")
        assertNotNull(result.publicKey)
    }

    @Test
    fun testDeriveAddressLegacy(): Unit = runBlocking {
        val derivationPath = AddressType.P2PKH.toDerivationPath(network = Network.BITCOIN)

        val result = onchainService.deriveBitcoinAddress(
            mnemonicPhrase = mnemonic,
            derivationPathStr = derivationPath,
            network = Network.BITCOIN,
            bip39Passphrase = null,
        )

        assertNotNull(result)
        assertNotNull(result.address)
        assertEquals(derivationPath, result.path)
        assertTrue(result.address.startsWith("1"), "Legacy address should start with 1")
        assertNotNull(result.publicKey)
    }

    @Test
    fun testDeriveAddressTestnet(): Unit = runBlocking {
        val derivationPath = AddressType.P2WPKH.toDerivationPath(network = Network.TESTNET)

        val result = onchainService.deriveBitcoinAddress(
            mnemonicPhrase = mnemonic,
            derivationPathStr = derivationPath,
            network = Network.TESTNET,
            bip39Passphrase = null,
        )

        assertNotNull(result)
        assertNotNull(result.address)
        assertEquals(derivationPath, result.path)
        assertTrue(result.address.startsWith("tb1"), "Testnet address should start with tb1")
        assertNotNull(result.publicKey)
    }

    @Test
    fun testDeriveAddressRegtest(): Unit = runBlocking {
        val network = Network.REGTEST
        val derivationPath = AddressType.P2WPKH.toDerivationPath(network = network)

        val result = onchainService.deriveBitcoinAddress(
            mnemonicPhrase = mnemonic,
            derivationPathStr = derivationPath,
            network = network,
            bip39Passphrase = null,
        )

        assertNotNull(result)
        assertNotNull(result.address)
        assertEquals(derivationPath, result.path)
        assertTrue(result.address.startsWith("bcrt1"), "Regtest address should start with bcrt1")
        assertNotNull(result.publicKey)
    }

    @Test
    fun testDeriveBitcoinDescriptorsForSupportedAccountTypes(): Unit = runBlocking {
        val cases = listOf(
            DescriptorCase(AccountType.LEGACY, "pkh([", "44"),
            DescriptorCase(AccountType.WRAPPED_SEGWIT, "sh(wpkh([", "49"),
            DescriptorCase(AccountType.NATIVE_SEGWIT, "wpkh([", "84"),
            DescriptorCase(AccountType.TAPROOT, "tr([", "86"),
        )

        cases.forEach { descriptorCase ->
            assertDescriptorShape(
                descriptorCase = descriptorCase,
                network = Network.BITCOIN,
                coinType = "0",
                publicKeyPrefix = "xpub",
            )
        }
    }

    @Test
    fun testDeriveRegtestDescriptorsForSupportedAccountTypes(): Unit = runBlocking {
        val cases = listOf(
            DescriptorCase(AccountType.LEGACY, "pkh([", "44"),
            DescriptorCase(AccountType.WRAPPED_SEGWIT, "sh(wpkh([", "49"),
            DescriptorCase(AccountType.NATIVE_SEGWIT, "wpkh([", "84"),
            DescriptorCase(AccountType.TAPROOT, "tr([", "86"),
        )

        cases.forEach { descriptorCase ->
            assertDescriptorShape(
                descriptorCase = descriptorCase,
                network = Network.REGTEST,
                coinType = "1",
                publicKeyPrefix = "tpub",
            )
        }
    }

    @Test
    fun testDeriveBitcoinNativeSegwitDescriptorWithPassphrase(): Unit = runBlocking {
        val descriptor = onchainService.deriveOnchainDescriptor(
            mnemonicPhrase = mnemonic,
            network = Network.BITCOIN,
            bip39Passphrase = "TREZOR",
            accountType = AccountType.NATIVE_SEGWIT,
            accountIndex = 0u,
        )
        val descriptorWithoutPassphrase = onchainService.deriveOnchainDescriptor(
            mnemonicPhrase = mnemonic,
            network = Network.BITCOIN,
            bip39Passphrase = null,
            accountType = AccountType.NATIVE_SEGWIT,
            accountIndex = 0u,
        )

        assertTrue(descriptor.startsWith("wpkh(["))
        assertTrue(descriptor.contains("/84'/0'/0']xpub"))
        assertTrue(descriptor.contains("/0/*"))
        assertFalse(descriptor == descriptorWithoutPassphrase)
    }

    private suspend fun assertDescriptorShape(
        descriptorCase: DescriptorCase,
        network: Network,
        coinType: String,
        publicKeyPrefix: String,
    ) {
        val descriptor = onchainService.deriveOnchainDescriptor(
            mnemonicPhrase = mnemonic,
            network = network,
            bip39Passphrase = null,
            accountType = descriptorCase.accountType,
            accountIndex = 0u,
        )

        assertTrue(descriptor.startsWith(descriptorCase.prefix))
        assertTrue(descriptor.contains("/${descriptorCase.purpose}'/$coinType'/0']$publicKeyPrefix"))
        assertTrue(descriptor.contains("/0/*"))
    }

    @Test
    fun testDeriveAddresses(): Unit = runBlocking {
        val derivationPath = AddressType.P2WPKH.toDerivationPath(network = Network.BITCOIN)
        val network = Network.BITCOIN
        val isChange = false
        val startIndex = 0u
        val count = 5u

        val result = onchainService.deriveBitcoinAddresses(
            mnemonicPhrase = mnemonic,
            derivationPathStr = derivationPath,
            network = network,
            bip39Passphrase = null,
            isChange = isChange,
            startIndex = startIndex,
            count = count,
        )

        assertNotNull(result)
        assertEquals(5, result.addresses.size)

        result.addresses.forEachIndexed { index, address ->
            assertNotNull(address.address)
            assertTrue(address.address.startsWith("bc1"), "All addresses should start with bc1")
            assertTrue(address.path.contains("/0/$index"), "Path should contain correct change (0) and index")
            assertNotNull(address.publicKey)
        }
    }

    @Test
    fun testDeriveAddressesChange(): Unit = runBlocking {
        val network = Network.BITCOIN

        val result = onchainService.deriveBitcoinAddresses(
            mnemonicPhrase = mnemonic,
            derivationPathStr = AddressType.P2WPKH.toDerivationPath(network = network),
            network = network,
            bip39Passphrase = null,
            isChange = true,
            startIndex = 0u,
            count = 3u,
        )

        assertNotNull(result)
        assertEquals(3, result.addresses.size)

        result.addresses.forEach { address ->
            assertNotNull(address.address)
            assertTrue(address.address.startsWith("bc1"), "All addresses should start with bc1")
            assertTrue(address.path.contains("/1/"), "Change addresses should have /1/ in path")
            assertNotNull(address.publicKey)
        }
    }

    @Test
    fun testDerivePrivateKey(): Unit = runBlocking {
        val network = Network.BITCOIN

        val result = onchainService.derivePrivateKey(
            mnemonicPhrase = mnemonic,
            derivationPathStr = AddressType.P2WPKH.toDerivationPath(network = network),
            network = network,
            bip39Passphrase = null,
        )

        assertNotNull(result)
        assertTrue(result.isNotBlank(), "Private key should not be blank")
        assertTrue(result.length >= 51, "Private key should be at least 51 characters for WIF format")
    }

    @Test
    fun testDerivePrivateKeyRegtest(): Unit = runBlocking {
        val network = Network.REGTEST

        val result = onchainService.derivePrivateKey(
            mnemonicPhrase = mnemonic,
            derivationPathStr = AddressType.P2WPKH.toDerivationPath(network = network),
            network = network,
            bip39Passphrase = null,
        )

        assertNotNull(result)
        assertTrue(result.isNotBlank(), "Private key should not be blank")
        assertTrue(result.length >= 51, "Private key should be at least 51 characters for WIF format")
    }

}

private data class DescriptorCase(
    val accountType: AccountType,
    val prefix: String,
    val purpose: String,
)
