package to.bitkit.repositories

import com.synonym.bitkitcore.UsdtDeposit
import com.synonym.bitkitcore.UsdtTransferStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.UsdtPreference
import to.bitkit.data.isUsdtEnabled
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.utils.AppError
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsdtPreferencesRepo @Inject constructor(
    private val settingsStore: SettingsStore,
    private val usdtRepo: UsdtRepo,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    val enabled = combine(settingsStore.data, usdtRepo.state) { settings, wallet ->
        settings.isUsdtEnabled(wallet)
    }
    val visible = combine(settingsStore.data, usdtRepo.state) { settings, wallet ->
        Env.isUsdtEnabled && (settings.isUsdtEnabled || wallet.hasFundsOrPendingTransfers)
    }
    val canDisable = usdtRepo.state.map { it.canDisable }

    suspend fun setEnabled(enabled: Boolean): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (!enabled && settingsStore.data.first().isUsdtEnabled(usdtRepo.state.value)) {
                checkCanDisable().getOrThrow()
            }
            settingsStore.update {
                it.copy(usdtPreference = if (enabled) UsdtPreference.ENABLED else UsdtPreference.DISABLED)
            }
        }
    }

    suspend fun checkCanDisable(): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            usdtRepo.refresh(history = false, force = true).getOrThrow()
            if (usdtRepo.state.value.balance != 0uL) throw UsdtDisableError()
            if (Env.usdtDepositsUrl != null) {
                usdtRepo.operation {
                    var offset: UInt? = 0u
                    while (offset != null) {
                        val page = depositHistory(offset)
                        usdtRepo.updateDeposits(page.deposits)
                        if (page.deposits.any { it.needsSettlement }) throw UsdtDisableError()
                        val next = page.nextOffset
                        if (next != null && next <= offset) throw UsdtDisableError()
                        offset = next
                    }
                }.getOrThrow()
            }
            if (!usdtRepo.state.value.canDisable) throw UsdtDisableError()
        }
    }
}

internal val UsdtWalletState.hasFundsOrPendingTransfers: Boolean
    get() = hasKnownFundsOrPendingTransfers || (balance == null && transfers.isNotEmpty())

internal val UsdtWalletState.hasKnownFundsOrPendingTransfers: Boolean
    get() = pendingDepositIds.isNotEmpty() || (balance ?: 0uL) > 0uL || transfers.any {
        it.status in setOf(
            UsdtTransferStatus.PENDING, UsdtTransferStatus.BRIDGING,
            UsdtTransferStatus.BRIDGE_NEEDS_ATTENTION, UsdtTransferStatus.BRIDGE_FAILED,
        )
    }

internal val UsdtWalletState.canDisable: Boolean
    get() = balance == 0uL && !hasFundsOrPendingTransfers

class UsdtDisableError : AppError("USDT balance or transfers need attention")

internal val UsdtDeposit.needsSettlement: Boolean
    get() = status !in setOf("completed", "refunded", "waiting", "awaiting_deposit", "expired")

internal fun SettingsData.isUsdtEnabled(wallet: UsdtWalletState): Boolean =
    Env.isUsdtEnabled && (isUsdtEnabled || wallet.hasKnownFundsOrPendingTransfers)
