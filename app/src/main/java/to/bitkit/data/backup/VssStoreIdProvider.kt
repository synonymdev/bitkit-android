package to.bitkit.data.backup

import com.synonym.vssclient.vssDeriveStoreId
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
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

            val mnemonicSecret = keychain.loadSecret(Keychain.Key.BIP39_MNEMONIC.name)
                ?: throw ServiceError.MnemonicNotFound()
            val passphraseSecret = keychain.loadSecret(Keychain.Key.BIP39_PASSPHRASE.name)

            val storeId = vssDeriveStoreId(
                prefix = Env.vssStoreIdPrefix,
                mnemonic = mnemonicSecret.use { String(it) },
                passphrase = passphraseSecret?.use { String(it) },
            )

            cacheMap[walletIndex] = storeId
            Logger.info("VSS store id setup for wallet[$walletIndex]: '$storeId'", context = TAG)
            return storeId
        }
    }

    fun clearCache() {
        cacheMap.clear()
    }

    companion object {
        private const val TAG = "VssStoreIdProvider"
    }
}
