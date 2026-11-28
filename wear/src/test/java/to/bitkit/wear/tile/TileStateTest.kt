package to.bitkit.wear.tile

import org.junit.Test
import to.bitkit.wear.repositories.WalletState
import to.bitkit.wear.repositories.WalletStatus
import to.bitkit.wear.ui.screens.home.DisplayAmount
import java.util.Locale
import kotlin.test.assertEquals

class TileStateTest {
    @Test
    fun `without a wallet the tile only opens the app`() {
        assertEquals(TileState.SignedOut, tileState(hasWallet = false, WalletState(WalletStatus.Idle), Locale.US))
    }

    @Test
    fun `a removed wallet signs the tile out before its seed is gone`() {
        val synced = WalletState(WalletStatus.Ready, balanceSats = 1_000uL, hasSynced = true)

        assertEquals(
            TileState.SignedOut,
            tileState(hasWallet = true, synced.copy(status = WalletStatus.Missing), Locale.US),
        )
        assertEquals(
            TileState.SignedOut,
            tileState(hasWallet = true, synced.copy(status = WalletStatus.Removed), Locale.US),
        )
    }

    @Test
    fun `a synced balance shows in bitcoin`() {
        val wallet = WalletState(WalletStatus.Ready, balanceSats = 125_000uL, hasSynced = true)

        assertEquals(
            TileState.Wallet(DisplayAmount("₿", "125 000")),
            tileState(hasWallet = true, wallet, Locale.US),
        )
    }

    @Test
    fun `the balance stays hidden until a sync of this process confirmed it`() {
        val notLoaded = WalletState(WalletStatus.Idle)
        val unsynced = WalletState(WalletStatus.Connecting, balanceSats = 0uL, hasSynced = false)
        val failed = WalletState(WalletStatus.Error, balanceSats = 5_000uL, hasSynced = false)

        assertEquals(TileState.Wallet(balance = null), tileState(hasWallet = true, notLoaded, Locale.US))
        assertEquals(TileState.Wallet(balance = null), tileState(hasWallet = true, unsynced, Locale.US))
        assertEquals(TileState.Wallet(balance = null), tileState(hasWallet = true, failed, Locale.US))
    }

    @Test
    fun `a paused or offline wallet keeps the balance it last synced`() {
        val paused = WalletState(WalletStatus.Paused, balanceSats = 1_000uL, hasSynced = true)
        val offline = paused.copy(status = WalletStatus.Offline)

        assertEquals(TileState.Wallet(DisplayAmount("₿", "1 000")), tileState(hasWallet = true, paused, Locale.US))
        assertEquals(TileState.Wallet(DisplayAmount("₿", "1 000")), tileState(hasWallet = true, offline, Locale.US))
    }

    @Test
    fun `a locked watch hides the balance`() {
        val locked = WalletState(WalletStatus.Locked, balanceSats = 1_000uL, hasSynced = true)

        assertEquals(TileState.Wallet(balance = null), tileState(hasWallet = true, locked, Locale.US))
    }
}
