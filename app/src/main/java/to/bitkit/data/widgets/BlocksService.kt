package to.bitkit.data.widgets

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import to.bitkit.data.dto.BlockDTO
import to.bitkit.data.dto.MempoolBlockInfo
import to.bitkit.env.Env
import to.bitkit.models.WidgetType
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

@Singleton
class BlocksService @Inject constructor(
    private val client: HttpClient,
) : WidgetService<BlockDTO> {

    override val widgetType = WidgetType.BLOCK
    override val refreshInterval = 9.minutes

    override suspend fun fetchData(): Result<BlockDTO> = runCatching {
        // First get the tip hash
        val tipHash = getTipHash()
        // Then get the block info
        val blockInfo = getBlockInfo(tipHash)
        // Format and return as BlockDTO
        formatBlockInfo(blockInfo)
    }.onFailure {
        Logger.warn(e = it, msg = "Failed to fetch block data", context = TAG)
    }

    private suspend fun getTipHash(): String {
        val response: HttpResponse = client.get("${Env.mempoolBaseUrl}/blocks/tip/hash")
        return when (response.status.isSuccess()) {
            true -> response.body<String>()
            else -> throw BlockError.InvalidResponse("Failed to fetch tip hash: ${response.status.description}")
        }
    }

    private suspend fun getBlockInfo(hash: String): MempoolBlockInfo {
        val response: HttpResponse = client.get("${Env.mempoolBaseUrl}/block/$hash")
        return when (response.status.isSuccess()) {
            true -> {
                val responseBody = runCatching { response.body<MempoolBlockInfo>() }.getOrElse {
                    throw BlockError.InvalidResponse(it.message.orEmpty())
                }
                responseBody
            }

            else -> throw BlockError.InvalidResponse("Failed to fetch block info: ${response.status.description}")
        }
    }

    private fun formatBlockInfo(blockInfo: MempoolBlockInfo): BlockDTO {
        val numberFormat = NumberFormat.getNumberInstance(Locale.US)

        // Format difficulty (convert to trillions)
        val difficulty = String.format("%.2f", blockInfo.difficulty / 1_000_000_000_000.0)

        // Format size (convert to KB)
        val sizeKb = (blockInfo.size / 1024.0)
        val formattedSize = "${numberFormat.format(sizeKb.toInt())} KB"

        // Format weight (convert to MWU - Million Weight Units)
        val weightMwu = (blockInfo.weight / 1024.0 / 1024.0)
        val formattedWeight = "${numberFormat.format(weightMwu.toInt())} MWU"

        // Format other numbers
        val formattedHeight = numberFormat.format(blockInfo.height)
        val formattedTransactionCount = numberFormat.format(blockInfo.txCount)

        // Format timestamp to date and time
        val timestamp = blockInfo.timestamp * 1000L // Convert to milliseconds

        return BlockDTO(
            hash = blockInfo.id,
            height = formattedHeight,
            timestamp = timestamp,
            transactionCount = formattedTransactionCount,
            size = formattedSize,
            weight = formattedWeight,
            difficulty = difficulty,
            merkleRoot = blockInfo.merkleRoot,
            source = Env.mempoolBaseUrl.replace("https://", "").replaceAfter("/", "")
        )
    }

    companion object {
        private const val TAG = "BlocksService"
    }
}


/**
 * Block-specific error types
 */
sealed class BlockError(message: String) : AppError(message) {
    data class InvalidResponse(override val message: String) : BlockError(message)
}
