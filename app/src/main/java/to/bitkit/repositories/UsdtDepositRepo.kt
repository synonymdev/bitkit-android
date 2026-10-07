package to.bitkit.repositories

import com.synonym.bitkitcore.UsdtDepositNetwork
import kotlinx.collections.immutable.toImmutableList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsdtDepositRepo @Inject constructor(private val usdt: UsdtRepo) {
    suspend fun depositNetworks() = usdt.operation { depositNetworks().toImmutableList() }
    suspend fun prepareDeposit(network: UsdtDepositNetwork, amount: String) =
        usdt.operation { prepareDeposit(network, amount) }
    suspend fun depositHistory(offset: UInt) = usdt.operation { depositHistory(offset) }
    suspend fun depositDetail(id: String, offset: UInt) = usdt.operation { depositDetail(id, offset) }
    suspend fun refundDeposit(id: String, offset: UInt, address: String, network: UsdtDepositNetwork) =
        usdt.operation { refundDeposit(id, offset, address, network) }
}
