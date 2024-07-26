package to.bitkit.bdk

import android.util.Log
import org.bitcoindevkit.AddressIndex
import org.bitcoindevkit.Blockchain
import org.bitcoindevkit.BlockchainConfig
import org.bitcoindevkit.DatabaseConfig
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.EsploraConfig
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Progress
import org.bitcoindevkit.Wallet
import to.bitkit.Env
import to.bitkit.REST
import to.bitkit.SEED
import to.bitkit.Tag.BDK

internal class BitcoinService {
    companion object {
        val shared by lazy {
            BitcoinService()
        }
    }

    private val wallet by lazy {
        val network = Env.Network.bdk
        val mnemonic = Mnemonic.fromString(SEED)
        val key = DescriptorSecretKey(network, mnemonic, null)

        Wallet(
            descriptor = Descriptor.newBip84(key, KeychainKind.INTERNAL, network),
            changeDescriptor = Descriptor.newBip84(key, KeychainKind.EXTERNAL, network),
            network = network,
            databaseConfig = DatabaseConfig.Memory,
        )
    }
    private val blockchain by lazy {
        Blockchain(
            BlockchainConfig.Esplora(
                EsploraConfig(
                    baseUrl = REST,
                    proxy = null,
                    concurrency = 5u,
                    stopGap = 20u,
                    timeout = null,
                )
            )
        )
    }

    fun sync() {
        wallet.sync(
            blockchain = blockchain,
            progress = object : Progress {
                override fun update(progress: Float, message: String?) {
                    Log.d(BDK, "Updating wallet: $progress $message")
                }
            }
        )
    }

    // region State
    val balance get() = wallet.getBalance()
    val address get() = wallet.getAddress(AddressIndex.LastUnused).address.asString()
}
