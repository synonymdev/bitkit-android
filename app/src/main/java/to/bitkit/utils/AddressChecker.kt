package to.bitkit.utils

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import to.bitkit.env.Env
import javax.inject.Inject
import javax.inject.Singleton

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

    suspend fun getTransaction(txid: String): TxDetails {
        try {
            val response = client.get("${Env.esploraServerUrl}/tx/$txid")
            if (!response.status.isSuccess()) {
                throw AddressCheckerError.InvalidResponse
            }
            return response.body<TxDetails>()
        } catch (_: ClientRequestException) {
            throw AddressCheckerError.InvalidResponse
        } catch (_: SerializationException) {
            throw AddressCheckerError.InvalidResponse
        } catch (e: Exception) {
            throw AddressCheckerError.NetworkError(e)
        }
    }
}

@Suppress("PropertyName")
@Serializable
data class AddressStats(
    val funded_txo_count: Int,
    val funded_txo_sum: Int,
    val spent_txo_count: Int,
    val spent_txo_sum: Int,
    val tx_count: Int,
)

@Suppress("PropertyName")
@Serializable
data class AddressInfo(
    val address: String,
    val chain_stats: AddressStats,
    val mempool_stats: AddressStats,
)

@Suppress("SpellCheckingInspection", "PropertyName")
@Serializable
data class TxInput(
    val txid: String? = null,
    val vout: Int? = null,
    val prevout: TxOutput? = null,
    val scriptsig: String? = null,
    val scriptsig_asm: String? = null,
    val witness: List<String>? = null,
    val is_coinbase: Boolean? = null,
    val sequence: Long? = null,
)

@Suppress("SpellCheckingInspection", "PropertyName")
@Serializable
data class TxOutput(
    val scriptpubkey: String,
    val scriptpubkey_asm: String? = null,
    val scriptpubkey_type: String? = null,
    val scriptpubkey_address: String? = null,
    val value: Long,
    val n: Int? = null,
)

@Serializable
data class TxDetails(
    val txid: String,
    val vin: List<TxInput>,
    val vout: List<TxOutput>,
)

sealed class AddressCheckerError(message: String? = null) : AppError(message) {
    data class NetworkError(val error: Throwable) : AddressCheckerError(error.message)
    data object InvalidResponse : AddressCheckerError()
}
