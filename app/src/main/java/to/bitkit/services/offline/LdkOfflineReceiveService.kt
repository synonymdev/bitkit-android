package to.bitkit.services.offline

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.nowMillis
import to.bitkit.ext.runSuspendCatching
import to.bitkit.services.OfflineReceiveInvoiceParser
import to.bitkit.services.OfflineReceiveRequest
import to.bitkit.services.OfflineReceiveService
import to.bitkit.services.OfflineReceiveUnavailable
import to.bitkit.services.PreparedOfflineInvoice
import to.bitkit.services.offline.OfflineReceiveClientException.Kind
import to.bitkit.utils.Logger
import javax.inject.Inject

/**
 * Offline receive provider backed by the ldk-node offline receive handler through [OfflineReceiveClient].
 *
 * Preparation starts or resumes the library request under a durable client request id, polls its status until the
 * library reports `Ready`, and returns the invoice only after it was parsed and matched against the request. The
 * persisted request identity survives process death: a later request for the same amount and description reuses it,
 * so the library resumes the existing request instead of preparing a new one. The identity is cleared when the
 * library reports a terminal status, when a different intent supersedes it, or on [cancelActiveRequest].
 * A timeout or caller cancellation keeps the identity so the request can be resumed.
 */
class LdkOfflineReceiveService internal constructor(
    private val clientProvider: OfflineReceiveClientProvider,
    private val requestStore: OfflineReceiveRequestStore,
    private val invoiceParser: OfflineReceiveInvoiceParser,
    private val settingsSource: OfflineReceiveSettingsSource,
    private val ioDispatcher: CoroutineDispatcher,
    private val pollIntervalMillis: Long,
) : OfflineReceiveService {

    @Inject
    constructor(
        clientProvider: OfflineReceiveClientProvider,
        requestStore: OfflineReceiveRequestStore,
        invoiceParser: OfflineReceiveInvoiceParser,
        settingsSource: OfflineReceiveSettingsSource,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        clientProvider = clientProvider,
        requestStore = requestStore,
        invoiceParser = invoiceParser,
        settingsSource = settingsSource,
        ioDispatcher = ioDispatcher,
        pollIntervalMillis = DEFAULT_POLL_INTERVAL_MILLIS,
    )

    private val prepareMutex = Mutex()

    override suspend fun canReceive(amountSats: ULong): Result<Boolean> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (amountSats == 0uL || amountSats > MAX_AMOUNT_SATS) return@runSuspendCatching false
            if (!settingsSource.current().isEnabled) return@runSuspendCatching false
            val client = clientProvider.client() ?: return@runSuspendCatching false
            try {
                client.canReceive(amountSats * MSAT_PER_SAT)
            } catch (e: OfflineReceiveClientException) {
                when (e.kind) {
                    Kind.DISABLED, Kind.UNAVAILABLE, Kind.INELIGIBLE, Kind.NOT_RUNNING -> false
                    else -> throw e
                }
            }
        }
    }

    override suspend fun prepareInvoice(request: OfflineReceiveRequest): Result<PreparedOfflineInvoice> =
        withContext(ioDispatcher) {
            prepareMutex.withLock {
                runSuspendCatching { prepare(request) }.fold(
                    onSuccess = { Result.success(it) },
                    onFailure = { Result.failure(it.asUnavailable()) },
                )
            }
        }

    /** Cancels the persisted active request in the library, if any, and forgets its identity. */
    suspend fun cancelActiveRequest(): Result<Unit> = withContext(ioDispatcher) {
        prepareMutex.withLock {
            runSuspendCatching {
                val persisted = requestStore.load() ?: return@runSuspendCatching
                clientProvider.client()?.let { cancelQuietly(it, persisted.requestId) }
                requestStore.clear()
            }
        }
    }

    private suspend fun prepare(request: OfflineReceiveRequest): PreparedOfflineInvoice {
        val settings = settingsSource.current()
        if (!settings.isEnabled) throw OfflineReceiveUnavailable()
        if (request.requestId.isBlank() || request.amountSats == 0uL || request.amountSats > MAX_AMOUNT_SATS) {
            throw OfflineReceiveUnavailable()
        }
        val client = clientProvider.client() ?: throw OfflineReceiveUnavailable()
        val requestId = resolveRequestId(client, request)
        val amountMsat = request.amountSats * MSAT_PER_SAT
        val recovered = statusOrNull(client, requestId) as? OfflineReceiveClientStatus.Ready
        val initial = recovered ?: client.prepare(requestId, amountMsat, request.description)
        if (recovered != null) {
            Logger.info("Recovered ready offline receive request $requestId", context = TAG)
        }
        val ready = awaitReady(client, requestId, initial, settings.prepareTimeoutMillis)
        return validate(client, request, ready.bolt11).onFailure {
            Logger.warn("Offline receive invoice rejected for request $requestId", it, context = TAG)
            cancelQuietly(client, requestId)
            requestStore.clear()
        }.getOrThrow()
    }

    /**
     * Reuses the persisted request id for an identical intent that the library still tracks; otherwise cancels a
     * superseded request and persists the new identity before the library is asked to prepare.
     */
    private suspend fun resolveRequestId(client: OfflineReceiveClient, request: OfflineReceiveRequest): String {
        val persisted = requestStore.load()
        if (persisted != null) {
            val sameIntent = persisted.amountSats == request.amountSats && persisted.description == request.description
            if (persisted.requestId == request.requestId) {
                if (!sameIntent) throw OfflineReceiveClientException(Kind.REQUEST_CONFLICT)
                return persisted.requestId
            }
            val status = statusOrNull(client, persisted.requestId)
            if (sameIntent && status != null && !status.isTerminal) {
                Logger.info("Reusing persisted offline receive request ${persisted.requestId}", context = TAG)
                return persisted.requestId
            }
            if (status != null && !status.isTerminal) cancelQuietly(client, persisted.requestId)
            requestStore.clear()
        }
        requestStore.save(PersistedOfflineReceiveRequest(request.requestId, request.amountSats, request.description))
        return request.requestId
    }

    private suspend fun awaitReady(
        client: OfflineReceiveClient,
        requestId: String,
        initial: OfflineReceiveClientStatus,
        timeoutMillis: Long,
    ): OfflineReceiveClientStatus.Ready {
        var status = initial
        val ready = withTimeoutOrNull(timeoutMillis) {
            while (status !is OfflineReceiveClientStatus.Ready) {
                if (status.isTerminal) {
                    Logger.warn("Offline receive request $requestId ended with $status", context = TAG)
                    requestStore.clear()
                    throw OfflineReceiveUnavailable()
                }
                delay(pollIntervalMillis)
                status = client.status(requestId)
            }
            status as OfflineReceiveClientStatus.Ready
        }
        if (ready == null) {
            Logger.warn("Offline receive request $requestId not ready after $timeoutMillis ms", context = TAG)
            throw OfflineReceiveUnavailable()
        }
        return ready
    }

    private fun validate(
        client: OfflineReceiveClient,
        request: OfflineReceiveRequest,
        bolt11: String,
    ): Result<PreparedOfflineInvoice> = runCatching {
        val details = invoiceParser.parse(bolt11)
        val expirySeconds = details.timestampSeconds + details.expirySeconds
        check(bolt11.isNotBlank())
        check(details.network == Env.network)
        check(details.amountMsat == request.amountSats * MSAT_PER_SAT)
        check(details.description == request.description)
        check(details.payeePubkey == client.nodeId())
        check(expirySeconds >= details.timestampSeconds && expirySeconds <= Long.MAX_VALUE.toULong() / MSAT_PER_SAT)
        val expiresAtMillis = (expirySeconds * MSAT_PER_SAT).toLong()
        check(expiresAtMillis > nowMillis())
        PreparedOfflineInvoice(
            bolt11 = bolt11,
            amountSats = request.amountSats,
            description = request.description,
            expiresAtMillis = expiresAtMillis,
            paymentHash = details.paymentHash,
        )
    }

    private fun statusOrNull(client: OfflineReceiveClient, requestId: String): OfflineReceiveClientStatus? = try {
        client.status(requestId)
    } catch (e: OfflineReceiveClientException) {
        if (e.kind == Kind.REQUEST_NOT_FOUND) null else throw e
    }

    private fun cancelQuietly(client: OfflineReceiveClient, requestId: String) {
        try {
            client.cancel(requestId)
        } catch (e: OfflineReceiveClientException) {
            Logger.warn("Cancel of offline receive request $requestId failed", e, context = TAG)
        }
    }

    private fun Throwable.asUnavailable(): Throwable =
        if (this is OfflineReceiveUnavailable) this else OfflineReceiveUnavailable(this)

    companion object {
        private const val TAG = "LdkOfflineReceiveService"
        const val DEFAULT_POLL_INTERVAL_MILLIS = 500L
        private const val MSAT_PER_SAT = 1_000uL
        private val MAX_AMOUNT_SATS = ULong.MAX_VALUE / MSAT_PER_SAT
    }
}
