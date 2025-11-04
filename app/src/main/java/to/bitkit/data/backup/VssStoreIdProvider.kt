package to.bitkit.data.backup

import com.synonym.vssclient.vssDeriveStoreId
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VssStoreIdProvider @Inject constructor(
    private val keychain: Keychain,
) {
    @Volatile
    private var cachedStoreIds: MutableMap<Int, String> = mutableMapOf()

    fun getVssStoreId(walletIndex: Int = 0): String {
        cachedStoreIds[walletIndex]?.let { return it }

        return synchronized(this) {
            cachedStoreIds[walletIndex]?.let { return it }

            val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw ServiceError.MnemonicNotFound
            val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)

            val storeId = vssDeriveStoreId(
                prefix = Env.vssStoreIdPrefix,
                mnemonic = mnemonic,
                passphrase = passphrase,
            )

            Logger.info("VSS store id: '$storeId' for walletIndex: $walletIndex", context = TAG)
            cachedStoreIds[walletIndex] = storeId
            storeId
        }
    }

    fun clearCache() {
        synchronized(this) {
            cachedStoreIds.clear()
        }
    }

    fun clearCache(walletIndex: Int) {
        synchronized(this) {
            cachedStoreIds.remove(walletIndex)
        }
    }

    companion object {
        private const val TAG = "VssStoreIdProvider"
    }
}
