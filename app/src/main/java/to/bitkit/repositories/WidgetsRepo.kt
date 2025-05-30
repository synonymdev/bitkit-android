package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import to.bitkit.data.WidgetsStore
import to.bitkit.data.widgets.NewsService
import to.bitkit.di.BgDispatcher
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

@Singleton
class WidgetsRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val newsService: NewsService,
    private val widgetsStore: WidgetsStore
) {
    private val refreshInterval = 10.minutes

    val articlesFlow = widgetsStore.data

    suspend fun updateArticlesInLoop() {
        updateArticles()
        delay(refreshInterval)
        updateArticlesInLoop()
    }

    suspend fun updateArticles(): Result<Unit> = withContext(bgDispatcher) {
        return@withContext try {
            val news = newsService.fetchLatestNews().take(10)
            widgetsStore.updateArticles(news)

            Result.success(Unit)
        } catch (e: Exception) {
            Logger.warn(e = e, msg = e.message, context = TAG)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "WidgetsRepo"
    }
}
