package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import to.bitkit.data.WidgetsStore
import to.bitkit.data.widgets.NewsService
import to.bitkit.di.BgDispatcher
import to.bitkit.models.widget.toNewsModel
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
    private val refreshInterval = 2.minutes

    val articlesFlow = widgetsStore.data.map { it.articles.map { article -> article.toNewsModel() } }

    suspend fun updateNewsInLoop() {
        updateNews()
        delay(refreshInterval)
        updateNewsInLoop()
    }

    suspend fun updateNews(): Result<Unit> = withContext(bgDispatcher) {
        return@withContext try {
            val news = newsService.fetchLatestNews().take(5)
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
