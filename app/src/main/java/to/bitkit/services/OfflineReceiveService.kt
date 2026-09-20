package to.bitkit.services

import androidx.compose.runtime.Immutable
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.Bolt11InvoiceDescription
import org.lightningdevkit.ldknode.Network
import to.bitkit.utils.AppError
import javax.inject.Inject

/**
 * Native FFOR boundary. Eligibility must check a compatible settlement peer and the exact amount.
 * Preparation must durably activate a recoverable voucher before returning an invoice. Implementations
 * must retain recovery state even when the caller is cancelled, and must never return an ordinary invoice.
 */
interface OfflineReceiveService {
    suspend fun canReceive(amountSats: ULong): Result<Boolean>
    suspend fun prepareInvoice(request: OfflineReceiveRequest): Result<PreparedOfflineInvoice>
}

@Immutable
data class OfflineReceiveRequest(
    val requestId: String,
    val amountSats: ULong,
    val description: String,
)

@Immutable
data class PreparedOfflineInvoice(
    val bolt11: String,
    val amountSats: ULong,
    val description: String,
    val expiresAtMillis: Long,
    val paymentHash: String,
)

class OfflineReceiveInvoiceParser @Inject constructor() {
    fun parse(bolt11: String): OfflineInvoiceDetails {
        val invoice = Bolt11Invoice.fromStr(bolt11)
        return OfflineInvoiceDetails(
            amountMsat = invoice.amountMilliSatoshis(),
            description = (invoice.invoiceDescription() as? Bolt11InvoiceDescription.Direct)?.description,
            network = invoice.network(),
            timestampSeconds = invoice.secondsSinceEpoch(),
            expirySeconds = invoice.expiryTimeSeconds(),
            paymentHash = invoice.paymentHash(),
        )
    }
}

data class OfflineInvoiceDetails(
    val amountMsat: ULong?,
    val description: String?,
    val network: Network,
    val timestampSeconds: ULong,
    val expirySeconds: ULong,
    val paymentHash: String,
)

/** The pinned native library has no FFOR receiver implementation. */
class UnavailableOfflineReceiveService @Inject constructor() : OfflineReceiveService {
    override suspend fun canReceive(amountSats: ULong): Result<Boolean> = Result.success(false)

    override suspend fun prepareInvoice(request: OfflineReceiveRequest): Result<PreparedOfflineInvoice> =
        Result.failure(OfflineReceiveUnavailable())
}

class OfflineReceiveUnavailable : AppError("Offline receive is unavailable")
