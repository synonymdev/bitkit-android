package to.bitkit.data.backup

import org.lightningdevkit.ldknode.Network
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.ext.toHex
import to.bitkit.ext.toSha256
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VssStoreIdProvider @Inject constructor(
    private val keychain: Keychain,
) {
    fun getVssStoreId(): String {
        // MARK: Temp fix as we don't have VSS auth yet
        if (Env.network != Network.REGTEST) {
            error("Do not run this on mainnet until VSS auth is implemented. Below hack is a temporary fix and not safe for mainnet.")
        }

        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw ServiceError.MnemonicNotFound
        val mnemonicData = mnemonic.encodeToByteArray()
        val hashedMnemonic = mnemonicData.toSha256()

        val storeIdHack = Env.vssStoreId + hashedMnemonic.toHex()
        Logger.info("storeIdHack: $storeIdHack")

        return storeIdHack
    }
}
