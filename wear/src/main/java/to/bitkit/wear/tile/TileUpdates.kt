package to.bitkit.wear.tile

import android.content.Context
import androidx.wear.tiles.TileService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.di.IoDispatcher
import to.bitkit.wear.repositories.WalletRepo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks the system to draw the tile again whenever what it shows may change: the balance, the bitcoin unit imported
 * from the phone, or the wallet's status, which also changes when a wallet is restored or removed. The first state of
 * a process counts too, so a tile drawn by an earlier process drops the balance this one has not loaded.
 */
@Singleton
class TileUpdates @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
    private val walletRepo: WalletRepo,
    private val settingsStore: SettingsStore,
) {
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    /** Follows the wallet for the life of the process. */
    fun start() {
        scope.launch {
            val units = settingsStore.data.map { it.displayUnit }.distinctUntilChanged()
            walletRepo.walletState
                .combine(units) { wallet, unit -> tileState(hasWallet = true, wallet, unit) to wallet.status }
                .distinctUntilChanged()
                .collect { TileService.getUpdater(context).requestUpdate(BalanceTileService::class.java) }
        }
    }
}
