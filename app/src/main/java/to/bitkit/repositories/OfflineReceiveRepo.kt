package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.nowMillis
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.USat
import to.bitkit.services.OfflineReceiveInvoiceParser
import to.bitkit.services.OfflineReceiveRequest
import to.bitkit.services.OfflineReceiveService
import to.bitkit.services.OfflineReceiveUnavailable
import to.bitkit.services.PreparedOfflineInvoice
import javax.inject.Inject

class OfflineReceiveRepo @Inject constructor(
    private val service: OfflineReceiveService,
    private val walletRepo: WalletRepo,
    private val lightningRepo: LightningRepo,
    private val invoiceParser: OfflineReceiveInvoiceParser,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val preparationMutex = Mutex()
    private var attemptedRequest: OfflineReceiveRequest? = null

    suspend fun canReceive(amountSats: ULong): Result<Boolean> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (amountSats == 0uL || amountSats > ULong.MAX_VALUE / 1_000uL ||
                !lightningRepo.lightningState.value.nodeLifecycleState.isRunning()
            ) {
                return@runSuspendCatching false
            }
            if (amountSats > walletRepo.inboundLiquiditySats()) return@runSuspendCatching false
            service.canReceive(amountSats).getOrThrow()
        }
    }

    suspend fun prepareInvoice(request: OfflineReceiveRequest): Result<PreparedOfflineInvoice> =
        withContext(ioDispatcher) {
            preparationMutex.withLock {
                runSuspendCatching { prepareValidatedInvoice(request) }
            }
        }

    private suspend fun prepareValidatedInvoice(request: OfflineReceiveRequest): PreparedOfflineInvoice {
        val amountSats = request.amountSats
        val description = request.description
        checkAvailable(request.requestId.isNotBlank())
        checkAvailable(attemptedRequest?.requestId != request.requestId || attemptedRequest == request)
        if (attemptedRequest != request) {
            checkAvailable(canReceive(amountSats).getOrThrow())
        }
        attemptedRequest = request
        val invoice = service.prepareInvoice(request).getOrThrow()
        checkAvailable(invoice.bolt11.isNotBlank())
        checkAvailable(invoice.amountSats == amountSats)
        checkAvailable(invoice.description == description)
        checkAvailable(invoice.expiresAtMillis > nowMillis())
        val decoded = invoiceParser.parse(invoice.bolt11)
        val expirySeconds = USat(decoded.timestampSeconds) + USat(decoded.expirySeconds)
        checkAvailable(decoded.network == Env.network)
        checkAvailable(decoded.amountMsat == USat(amountSats) * USat(1_000uL))
        checkAvailable(decoded.description == description)
        checkAvailable(decoded.paymentHash == invoice.paymentHash)
        checkAvailable(expirySeconds <= Long.MAX_VALUE.toULong() / 1_000uL)
        checkAvailable((USat(expirySeconds) * USat(1_000uL)).toLong() == invoice.expiresAtMillis)
        return invoice
    }

    private fun checkAvailable(condition: Boolean) {
        if (!condition) throw OfflineReceiveUnavailable()
    }
}
