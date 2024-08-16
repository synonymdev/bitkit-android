package to.bitkit.bdk

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.EsploraClient
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Wallet
import to.bitkit.Env
import to.bitkit.REST
import to.bitkit.SEED
import to.bitkit.Tag.BDK
import to.bitkit.async.BaseCoroutineScope
import to.bitkit.di.BgDispatcher
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

    private val wallet by lazy {
        val network = Env.network.bdk
        val mnemonic = Mnemonic.fromString(SEED)
        val key = DescriptorSecretKey(network, mnemonic, null)

        val dbPath = Path(Env.Storage.bdk, "db.sqlite").pathString

        Log.i(BDK, "Creating wallet…")

        Wallet(
            descriptor = Descriptor.newBip84(key, KeychainKind.INTERNAL, network),
            changeDescriptor = Descriptor.newBip84(key, KeychainKind.EXTERNAL, network),
            persistenceBackendPath = dbPath,
            network = network,
        )
    }

    // region sync
    fun sync() {
        Log.d(BDK, "Wallet syncing…")

        val request = wallet.startSyncWithRevealedSpks()
        val update = esploraClient.sync(request, parallelRequests)
        wallet.applyUpdate(update)

        hasSynced = true
        Log.d(BDK, "Wallet synced")
    }

    fun fullScan() {
        Log.d(BDK, "Wallet full scan…")

        val request = wallet.startFullScan()
        val update = esploraClient.fullScan(request, stopGap, parallelRequests)
        wallet.applyUpdate(update)
        // TODO: Persist wallet once BDK is updated to beta release

        hasSynced = true

        Log.d(BDK, "Wallet fully scanned")
    }
    // endregion

    // region state
    val balance get() = if (hasSynced) wallet.getBalance() else null
    val address get() = wallet.revealNextAddress(KeychainKind.EXTERNAL).address.asString()
    // endregion
}
