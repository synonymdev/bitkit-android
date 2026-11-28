package to.bitkit.wear.tile

import org.junit.Test
import to.bitkit.wear.models.BitcoinDisplayUnit.CLASSIC
import to.bitkit.wear.models.BitcoinDisplayUnit.MODERN
import to.bitkit.wear.repositories.WalletState
import to.bitkit.wear.repositories.WalletStatus
import to.bitkit.wear.ui.screens.home.DisplayAmount
import java.util.Locale
import kotlin.test.assertEquals

class TileStateTest {
    @Test
    fun `without a wallet the tile only opens the app`() {
        val wallet = WalletState(WalletStatus.Idle)

        assertEquals(TileState.SignedOut, tileState(hasWallet = false, wallet, MODERN, Locale.US))
    }

    @Test
    fun `a removed wallet signs the tile out before its seed is gone`() {
        val synced = WalletState(WalletStatus.Ready, balanceSats = 1_000uL, hasSynced = true)

        assertEquals(
            TileState.SignedOut,
            tileState(hasWallet = true, synced.copy(status = WalletStatus.Missing), MODERN, Locale.US),
        )
        assertEquals(
            TileState.SignedOut,
            tileState(hasWallet = true, synced.copy(status = WalletStatus.Removed), MODERN, Locale.US),
        )
    }

    @Test
    fun `a synced balance shows in bitcoin in the modern unit`() {
        val wallet = WalletState(WalletStatus.Ready, balanceSats = 125_000uL, hasSynced = true)

        assertEquals(
            TileState.Wallet(DisplayAmount("₿", "125 000")),
            tileState(hasWallet = true, wallet, MODERN, Locale.US),
        )
    }

    @Test
    fun `a synced balance shows in bitcoin in the classic unit`() {
        val wallet = WalletState(WalletStatus.Ready, balanceSats = 125_000uL, hasSynced = true)

        assertEquals(
            TileState.Wallet(DisplayAmount("₿", "0.00125000")),
            tileState(hasWallet = true, wallet, CLASSIC, Locale.US),
        )
    }

    @Test
    fun `the balance stays hidden until a sync of this process confirmed it`() {
        val notLoaded = WalletState(WalletStatus.Idle)
        val unsynced = WalletState(WalletStatus.Connecting, balanceSats = 0uL, hasSynced = false)
        val failed = WalletState(WalletStatus.Error, balanceSats = 5_000uL, hasSynced = false)

        assertEquals(TileState.Wallet(balance = null), tileState(hasWallet = true, notLoaded, MODERN, Locale.US))
        assertEquals(TileState.Wallet(balance = null), tileState(hasWallet = true, unsynced, MODERN, Locale.US))
        assertEquals(TileState.Wallet(balance = null), tileState(hasWallet = true, failed, MODERN, Locale.US))
    }

    @Test
    fun `a paused or offline wallet keeps the balance it last synced`() {
        val paused = WalletState(WalletStatus.Paused, balanceSats = 1_000uL, hasSynced = true)
        val offline = paused.copy(status = WalletStatus.Offline)

        val shown = TileState.Wallet(DisplayAmount("₿", "1 000"))

        assertEquals(shown, tileState(hasWallet = true, paused, MODERN, Locale.US))
        assertEquals(shown, tileState(hasWallet = true, offline, MODERN, Locale.US))
    }

    @Test
    fun `a locked watch hides the balance`() {
        val locked = WalletState(WalletStatus.Locked, balanceSats = 1_000uL, hasSynced = true)

        assertEquals(TileState.Wallet(balance = null), tileState(hasWallet = true, locked, MODERN, Locale.US))
    }
}
