package to.bitkit.services

import com.synonym.bitkitcore.AccountType
import org.lightningdevkit.ldknode.Network
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.repositories.WatchOnlyAccountError
import to.bitkit.utils.ServiceError
import java.math.BigInteger
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Derives the BIP84 account xpub (`m/84'/coin'/account'`) for a Paykit Server watch-only account from the wallet
 * mnemonic, so a Pubky auth approval does not wait for the LDK node to be built and started.
 *
 * The result must equal `Node.exportOnchainWalletAccountXpub`, which `Node.addOnchainWalletAccount` compares
 * byte for byte: bitkit-core always serializes with mainnet version bytes, while ldk-node uses the configured
 * network's, so test networks get the `tpub` version.
 */
@Singleton
class WatchOnlyAccountXpubDeriver @Inject constructor(
    private val keychain: Keychain,
    private val coreService: CoreService,
) {
    suspend fun derive(accountIndex: Int): String {
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw ServiceError.MnemonicNotFound()
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
        val descriptor = coreService.onchain.deriveOnchainDescriptor(
            mnemonicPhrase = mnemonic,
            network = Env.network,
            bip39Passphrase = passphrase,
            accountType = AccountType.NATIVE_SEGWIT,
            accountIndex = accountIndex.toUInt(),
        )
        return accountXpubForNetwork(descriptorAccountXpub(descriptor), Env.network)
    }
}

private val DESCRIPTOR_XPUB = Regex("""](xpub[1-9A-HJ-NP-Za-km-z]+)/""")
private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
private const val EXTENDED_KEY_PAYLOAD_LENGTH = 78
private const val VERSION_LENGTH = 4
private const val CHECKSUM_LENGTH = 4
private val TESTNET_XPUB_VERSION = byteArrayOf(0x04, 0x35, 0x87.toByte(), 0xCF.toByte())

internal fun descriptorAccountXpub(descriptor: String): String =
    DESCRIPTOR_XPUB.find(descriptor)?.groupValues?.get(1) ?: throw WatchOnlyAccountError.InvalidExtendedPublicKey()

internal fun accountXpubForNetwork(xpub: String, network: Network): String {
    if (network == Network.BITCOIN) return xpub
    val payload = base58CheckDecode(xpub)
    if (payload.size != EXTENDED_KEY_PAYLOAD_LENGTH) throw WatchOnlyAccountError.InvalidExtendedPublicKey()
    return base58CheckEncode(TESTNET_XPUB_VERSION + payload.copyOfRange(VERSION_LENGTH, payload.size))
}

private fun checksum(payload: ByteArray): ByteArray {
    val sha256 = MessageDigest.getInstance("SHA-256")
    return sha256.digest(sha256.digest(payload)).copyOfRange(0, CHECKSUM_LENGTH)
}

private fun base58CheckDecode(text: String): ByteArray {
    var value = BigInteger.ZERO
    for (char in text) {
        val digit = BASE58_ALPHABET.indexOf(char)
        if (digit < 0) throw WatchOnlyAccountError.InvalidExtendedPublicKey()
        value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
    }
    val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
    val decoded = ByteArray(text.takeWhile { it == '1' }.length) + bytes
    if (decoded.size <= CHECKSUM_LENGTH) throw WatchOnlyAccountError.InvalidExtendedPublicKey()
    val payload = decoded.copyOfRange(0, decoded.size - CHECKSUM_LENGTH)
    if (!checksum(payload).contentEquals(decoded.copyOfRange(payload.size, decoded.size))) {
        throw WatchOnlyAccountError.InvalidExtendedPublicKey()
    }
    return payload
}

private fun base58CheckEncode(payload: ByteArray): String {
    val bytes = payload + checksum(payload)
    var value = BigInteger(1, bytes)
    val fiftyEight = BigInteger.valueOf(58)
    val encoded = StringBuilder()
    while (value > BigInteger.ZERO) {
        val (quotient, remainder) = value.divideAndRemainder(fiftyEight)
        encoded.append(BASE58_ALPHABET[remainder.toInt()])
        value = quotient
    }
    repeat(bytes.takeWhile { it == 0.toByte() }.size) { encoded.append('1') }
    return encoded.reverse().toString()
}
