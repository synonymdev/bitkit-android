package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.BuildConfig
import to.bitkit.data.ChatwootHttpClient
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.models.ChatwootMessage
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupportRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val chatwootHttpClient: ChatwootHttpClient

) {

    suspend fun postQuestion(email: String, message: String) : Result<Unit> = withContext(bgDispatcher){
        return@withContext try {
            chatwootHttpClient.postQuestion(message = ChatwootMessage(
                email = email,
                message = message,
                platform = "${Env.PLATFORM} ${Env.androidSDKVersion}",
                version = "${BuildConfig.VERSION_NAME} ${BuildConfig.VERSION_CODE}",
                ldkVersion = "",
                ldkNodeId = "",
                logs = "",
                logsFileName = ""
            ))
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.error(msg = e.message, e = e, context = TAG)
            Result.failure(e)
        }
    }

    private companion object {
        const val TAG = "SupportRepo"
    }
}
