package to.bitkit.utils

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import to.bitkit.env.Env
import uniffi.bitkitcore.ActivityException.SerializationException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class AddressStats(
    val funded_txo_count: Int,
    val funded_txo_sum: Int,
    val spent_txo_count: Int,
    val spent_txo_sum: Int,
    val tx_count: Int,
)

@Serializable
data class AddressInfo(
    val address: String,
    val chain_stats: AddressStats,
    val mempool_stats: AddressStats,
)

sealed class AddressCheckerError(message: String? = null) : AppError(message) {
    data class NetworkError(val error: Throwable) : AddressCheckerError(error.message)
    data object InvalidResponse : AddressCheckerError()
}

/**
 * TEMPORARY IMPLEMENTATION
 * This is a short-term solution for getting address information using electrs.
 * Eventually, this will be replaced by similar features in bitkit-core or ldk-node
 * when they support native address lookup.
 */
@Singleton
class AddressChecker @Inject constructor(
    private val client: HttpClient,
) {
    suspend fun getAddressInfo(address: String): AddressInfo {
        try {
            val response = client.get("${Env.esploraServerUrl}/address/$address")
            if (!response.status.isSuccess()) {
                throw AddressCheckerError.InvalidResponse
            }
            return response.body<AddressInfo>()
        } catch (_: ClientRequestException) {
            throw AddressCheckerError.InvalidResponse
        } catch (_: SerializationException) {
            throw AddressCheckerError.InvalidResponse
        } catch (e: Exception) {
            throw AddressCheckerError.NetworkError(e)
        }
    }
}
