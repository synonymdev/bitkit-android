package to.bitkit.services

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LnUrlWithdrawService @Inject constructor(
    private val client: HttpClient,
) {
    suspend fun fetchData(lnUrlCallBack: String): Result<Unit> = runCatching {
        val response: HttpResponse = client.get(lnUrlCallBack)
    }.onFailure {
        Logger.warn(e = it, msg = "Failed to fetch data", context = TAG)
    }

    companion object {
        private const val TAG = "LnUrlWithdrawService"
    }
}
