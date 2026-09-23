package to.bitkit.services.offline

import to.bitkit.utils.AppError

/**
 * Minimal view of the ldk-node offline receive handler (`Node.offlineReceive()`), so the provider logic can be
 * exercised without the native library. Amounts are millisatoshis, as in the library.
 */
interface OfflineReceiveClient {
    fun nodeId(): String
    fun canReceive(amountMsat: ULong): Boolean
    fun prepare(requestId: String, amountMsat: ULong, description: String): OfflineReceiveClientStatus
    fun status(requestId: String): OfflineReceiveClientStatus
    fun cancel(requestId: String)
}

/** Resolves the client for the current node, or null while no node exists. */
fun interface OfflineReceiveClientProvider {
    fun client(): OfflineReceiveClient?
}

sealed interface OfflineReceiveClientStatus {
    /** Preparing, awaiting activation or awaiting witnesses. */
    data class Pending(val stage: String) : OfflineReceiveClientStatus

    /** The exact invoice was retained, confirmed and released by the library. */
    data class Ready(val bolt11: String) : OfflineReceiveClientStatus

    data object Expired : OfflineReceiveClientStatus

    data class Settled(val fulfilled: Boolean) : OfflineReceiveClientStatus

    data class Failed(val reason: String) : OfflineReceiveClientStatus

    val isTerminal: Boolean
        get() = this is Expired || this is Settled || this is Failed
}

class OfflineReceiveClientException(
    val kind: Kind,
    message: String? = null,
    cause: Throwable? = null,
) : AppError(message ?: kind.name, cause) {
    enum class Kind {
        DISABLED,
        UNAVAILABLE,
        INELIGIBLE,
        REQUEST_NOT_FOUND,
        REQUEST_CONFLICT,
        NOT_RUNNING,
        OTHER,
    }
}
