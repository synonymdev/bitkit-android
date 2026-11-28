package to.bitkit.wear.tile

import to.bitkit.wear.repositories.WalletState
import to.bitkit.wear.repositories.WalletStatus
import to.bitkit.wear.ui.screens.home.DisplayAmount
import to.bitkit.wear.ui.screens.home.bitcoinAmount
import java.util.Locale

/** What the Bitkit tile shows. */
sealed interface TileState {
    /** No wallet on the watch: the tile only opens the app. */
    data object SignedOut : TileState

    /** A wallet with its [balance], or null while it is hidden; Receive opens the QR. */
    data class Wallet(val balance: DisplayAmount?) : TileState
}

/**
 * The tile's state for a watch that [hasWallet] in [wallet]'s state. The balance shows only once a sync of this
 * process confirmed it, as on the Balance page, and never while the watch is locked; the tile never starts the wallet
 * to learn it.
 */
fun tileState(hasWallet: Boolean, wallet: WalletState, locale: Locale = Locale.getDefault()): TileState {
    if (!hasWallet || wallet.status in walletGoneStatuses) return TileState.SignedOut
    val sats = wallet.balanceSats?.takeIf { wallet.hasSynced && wallet.status != WalletStatus.Locked }?.toLong()
    return TileState.Wallet(sats?.let { bitcoinAmount(it, locale = locale) })
}

private val walletGoneStatuses = setOf(WalletStatus.Removed, WalletStatus.Missing)
