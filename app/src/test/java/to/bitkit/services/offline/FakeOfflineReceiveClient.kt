package to.bitkit.services.offline

import to.bitkit.services.offline.OfflineReceiveClientException.Kind

/** Scripted stand-in for the ldk-node offline receive handler. */
class FakeOfflineReceiveClient : OfflineReceiveClient {
    var nodeId = "node"
    var canReceiveResult = true
    var canReceiveError: OfflineReceiveClientException? = null
    var statusError: OfflineReceiveClientException? = null
    var prepareStatus: OfflineReceiveClientStatus = OfflineReceiveClientStatus.Pending("Preparing")
    val prepareCalls = mutableListOf<Triple<String, ULong, String>>()
    val statusCalls = mutableListOf<String>()
    val cancelCalls = mutableListOf<String>()
    private val known = mutableSetOf<String>()
    private val scripts = mutableMapOf<String, ArrayDeque<OfflineReceiveClientStatus>>()

    /** Statuses returned by consecutive `status` calls once the request is known; the last one repeats. */
    fun script(requestId: String, vararg statuses: OfflineReceiveClientStatus) {
        scripts[requestId] = ArrayDeque(statuses.toList())
    }

    /** Marks a request the library tracks from an earlier process, without a `prepare` call in this one. */
    fun track(requestId: String) {
        known += requestId
    }

    override fun nodeId(): String = nodeId

    override fun canReceive(amountMsat: ULong): Boolean {
        canReceiveError?.let { throw it }
        return canReceiveResult
    }

    override fun prepare(requestId: String, amountMsat: ULong, description: String): OfflineReceiveClientStatus {
        prepareCalls += Triple(requestId, amountMsat, description)
        known += requestId
        return prepareStatus
    }

    override fun status(requestId: String): OfflineReceiveClientStatus {
        statusCalls += requestId
        statusError?.let { throw it }
        if (requestId !in known) throw OfflineReceiveClientException(Kind.REQUEST_NOT_FOUND)
        val script = scripts[requestId] ?: return OfflineReceiveClientStatus.Pending("AwaitingActivation")
        return if (script.size > 1) script.removeFirst() else script.first()
    }

    override fun cancel(requestId: String) {
        cancelCalls += requestId
    }
}

class InMemoryOfflineReceiveRequestStore : OfflineReceiveRequestStore {
    var request: PersistedOfflineReceiveRequest? = null
    var saveCount = 0

    override suspend fun load(): PersistedOfflineReceiveRequest? = request

    override suspend fun save(request: PersistedOfflineReceiveRequest) {
        saveCount++
        this.request = request
    }

    override suspend fun clear() {
        request = null
    }
}
