package to.bitkit.services

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.bitcoindevkit.Balance
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.EsploraClient
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Wallet
import to.bitkit.async.BaseCoroutineScope
import to.bitkit.async.ServiceQueue
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.env.Env.SEED
import to.bitkit.env.Tag.BDK
import to.bitkit.shared.ServiceError
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.io.path.pathString

class OnChainService @Inject constructor(
    @BgDispatcher bgDispatcher: CoroutineDispatcher,
) : BaseCoroutineScope(bgDispatcher) {
    companion object {
        val shared by lazy {
            OnChainService(Dispatchers.Default)
        }
    }

    private val parallelRequests = 5_UL
    private val stopGap = 20_UL
    private var hasSynced = false

    private val esploraClient by lazy { EsploraClient(url = Env.esploraUrl) }
    private val dbPath by lazy { Path(Env.Storage.bdk, "db.sqlite") }

    private var wallet: Wallet? = null

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

    fun stop() {
        Log.d(BDK, "Stopping onchain wallet…")
        wallet?.close().also { wallet = null }
        Log.i(BDK, "Onchain wallet stopped")
    }

    fun wipeStorage() {
        if (wallet != null) throw ServiceError.OnchainWalletStillRunning

        Log.w(BDK, "Wiping onchain wallet storage…")
        dbPath.toFile()?.parentFile?.deleteRecursively()
        Log.i(BDK, "Onchain wallet storage wiped")
    }

    // region scan
    suspend fun syncWithRevealedSpks() {
        val wallet = this.wallet ?: throw ServiceError.OnchainWalletNotInitialized
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
        val wallet = this.wallet ?: throw ServiceError.OnchainWalletNotInitialized
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
    // endregion

    // region state
    val balance: Balance? get() = if (hasSynced) wallet?.getBalance() else null

    suspend fun getAddress(): String {
        val wallet = this.wallet ?: throw ServiceError.OnchainWalletNotInitialized

        return ServiceQueue.BDK.background {
            val addressInfo = wallet.revealNextAddress(KeychainKind.EXTERNAL)
            addressInfo.address.asString()
        }
    }
    // endregion
}
