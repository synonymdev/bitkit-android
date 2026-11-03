package to.bitkit.data.backup

import com.synonym.vssclient.vssDeriveStoreId
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VssStoreIdProvider @Inject constructor(
    private val keychain: Keychain,
) {
    private val cacheMap: MutableMap<Int, String> = ConcurrentHashMap()

    fun getVssStoreId(walletIndex: Int = 0): String {
        synchronized(this) {
            cacheMap[walletIndex]?.let { return it }

            val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
                ?: throw ServiceError.MnemonicNotFound()
            val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)

            val storeId = vssDeriveStoreId(
                prefix = Env.vssStoreIdPrefix,
                mnemonic = mnemonic,
                passphrase = passphrase,
            )

            cacheMap[walletIndex] = storeId
            Logger.info("VSS store id setup for wallet[$walletIndex]", context = TAG)
            return storeId
        }
    }

    fun getBackupWalletBinding(walletIndex: Int): String {
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name, walletIndex)
            ?: throw ServiceError.MnemonicNotFound()
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name, walletIndex)
        val indexedStoreId = vssDeriveStoreId(Env.vssStoreIdPrefix, mnemonic, passphrase)
        check(indexedStoreId == getVssStoreId()) { "Backup namespace does not match original indexed wallet" }
        return MessageDigest.getInstance("SHA-256").digest(indexedStoreId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun clearCache() {
        cacheMap.clear()
    }

    companion object {
        private const val TAG = "VssStoreIdProvider"
    }
}
