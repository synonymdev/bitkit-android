package to.bitkit.repositories

import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.IcJitEntry
import to.bitkit.utils.AppError
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chooses the Lightning service provider that opens channels. Blocktank is the only one today. A connected Pubky
 * swap provider takes over once pubky-swap supports channel orders, so channel orders go through this repo instead
 * of calling Blocktank directly. See https://github.com/coreyphillips/pubky-swap/issues/84.
 */
@Singleton
class ChannelProviderRepo @Inject constructor(
    private val blocktankRepo: BlocktankRepo,
) {
    fun current(): ChannelProvider = ChannelProvider.Blocktank

    suspend fun createOrder(spendingBalanceSats: ULong, receivingBalanceSats: ULong): Result<IBtOrder> =
        when (val provider = current()) {
            ChannelProvider.Blocktank -> blocktankRepo.createOrder(
                spendingBalanceSats = spendingBalanceSats,
                receivingBalanceSats = receivingBalanceSats,
            )

            is ChannelProvider.PubkySwap -> Result.failure(ChannelProviderUnsupportedError(provider.publicKey))
        }

    suspend fun createCjit(amountSats: ULong): Result<IcJitEntry> = when (val provider = current()) {
        ChannelProvider.Blocktank -> blocktankRepo.createCjit(amountSats)
        is ChannelProvider.PubkySwap -> Result.failure(ChannelProviderUnsupportedError(provider.publicKey))
    }
}

sealed interface ChannelProvider {
    data object Blocktank : ChannelProvider
    data class PubkySwap(val publicKey: String) : ChannelProvider
}

class ChannelProviderUnsupportedError(publicKey: String) :
    AppError("Swap provider '$publicKey' does not support channel orders yet")
