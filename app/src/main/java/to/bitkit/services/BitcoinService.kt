package to.bitkit.services

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.EsploraClient
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Wallet
import to.bitkit.env.Env
import to.bitkit.env.REST
import to.bitkit.env.SEED
import to.bitkit.env.Tag.BDK
import to.bitkit.async.BaseCoroutineScope
import to.bitkit.di.BgDispatcher
import to.bitkit.async.ServiceQueue
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.io.path.pathString

class BitcoinService @Inject constructor(
    @BgDispatcher bgDispatcher: CoroutineDispatcher,
) : BaseCoroutineScope(bgDispatcher) {
    companion object {
        val shared by lazy {
            BitcoinService(Dispatchers.Default)
        }
    }

    private val parallelRequests = 5_UL
    private val stopGap = 20_UL
    private var hasSynced = false

    private val esploraClient by lazy { EsploraClient(url = REST) }
    private val dbPath by lazy { Path(Env.Storage.bdk, "db.sqlite") }

    private lateinit var wallet: Wallet

    suspend fun setup() {
        val network = Env.network.bdk
        val mnemonic = Mnemonic.fromString(SEED)
        val key = DescriptorSecretKey(network, mnemonic, null)

        Log.d(BDK, "Setting up wallet…")

        ServiceQueue.BDK.background {
            wallet = Wallet(
                descriptor = Descriptor.newBip84(key, KeychainKind.INTERNAL, network),
                changeDescriptor = Descriptor.newBip84(key, KeychainKind.EXTERNAL, network),
                persistenceBackendPath = dbPath.pathString,
                network = network,
            )
        }

        Log.i(BDK, "Wallet set up")
    }

    suspend fun syncWithRevealedSpks() {
        Log.d(BDK, "Wallet syncing…")

        ServiceQueue.BDK.background {
            val request = wallet.startSyncWithRevealedSpks()
            val update = esploraClient.sync(request, parallelRequests)
            wallet.applyUpdate(update)
        }

        hasSynced = true
        Log.i(BDK, "Wallet synced")
    }

    suspend fun fullScan() {
        Log.d(BDK, "Wallet full scan…")

        ServiceQueue.BDK.background {
            val request = wallet.startFullScan()
            val update = esploraClient.fullScan(request, stopGap, parallelRequests)
            wallet.applyUpdate(update)
            // TODO: Persist wallet once BDK is updated to beta release
        }

        hasSynced = true

        Log.i(BDK, "Wallet fully scanned")
    }

    fun wipeStorage() {
        Log.w(BDK, "Wiping wallet storage…")

        dbPath.toFile()?.parentFile?.deleteRecursively()

        Log.i(BDK, "Wallet storage wiped")
    }

    // region state
    val balance get() = if (hasSynced) wallet.getBalance() else null

    suspend fun getNextAddress(): String {
        return ServiceQueue.BDK.background {
            val addressInfo = wallet.revealNextAddress(KeychainKind.EXTERNAL).address
            addressInfo.asString()
        }
    }
    // endregion
}
