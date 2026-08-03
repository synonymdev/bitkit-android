package to.bitkit.usecases

import kotlinx.coroutines.flow.first
import to.bitkit.data.SpendingBackend
import to.bitkit.repositories.BarkRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.WalletRepo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether the spending backend can be swapped right now.
 *
 * Switching is only safe with an empty spending balance: the two backends hold funds in completely
 * different places (channels vs VTXOs), and the inactive one is not synced, so any balance left
 * behind would silently disappear from the UI.
 */
@Singleton
class CanSwitchSpendingBackendUseCase @Inject constructor(
    private val walletRepo: WalletRepo,
    private val lightningRepo: LightningRepo,
    private val barkRepo: BarkRepo,
    private val transferRepo: TransferRepo,
) {
    suspend operator fun invoke(target: SpendingBackend): SpendingBackendSwitchState {
        val hasActiveTransfers = transferRepo.activeTransfers.first().isNotEmpty()
        if (hasActiveTransfers) return SpendingBackendSwitchState.Blocked.PendingTransfer

        return when (target) {
            SpendingBackend.BARK -> checkLeavingLdk()
            SpendingBackend.LDK -> checkLeavingBark()
        }
    }

    private fun checkLeavingLdk(): SpendingBackendSwitchState {
        val spendingSats = walletRepo.balanceState.value.totalLightningSats
        if (spendingSats > 0uL) return SpendingBackendSwitchState.Blocked.SpendingBalance(spendingSats)

        // A zero-balance channel still costs an on-chain close later, and would be orphaned while
        // the app reports Ark as the spending backend.
        if (lightningRepo.getChannels().orEmpty().isNotEmpty()) {
            return SpendingBackendSwitchState.Blocked.OpenChannels
        }
        return SpendingBackendSwitchState.Allowed
    }

    private suspend fun checkLeavingBark(): SpendingBackendSwitchState {
        val state = barkRepo.barkState.value
        if (state.hasAnyFunds) {
            return SpendingBackendSwitchState.Blocked.SpendingBalance(state.spendableSats)
        }
        val hasPendingExits = barkRepo.hasPendingExits().getOrDefault(false)
        if (hasPendingExits) return SpendingBackendSwitchState.Blocked.PendingExit
        return SpendingBackendSwitchState.Allowed
    }
}

sealed interface SpendingBackendSwitchState {
    data object Allowed : SpendingBackendSwitchState

    sealed interface Blocked : SpendingBackendSwitchState {
        /** Funds must be moved to savings before the backend can change. */
        data class SpendingBalance(val sats: ULong) : Blocked

        data object OpenChannels : Blocked
        data object PendingTransfer : Blocked
        data object PendingExit : Blocked
    }
}
