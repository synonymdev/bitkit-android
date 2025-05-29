package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.data.widgets.NewsService
import to.bitkit.di.BgDispatcher
import to.bitkit.models.widget.NewsModel
import to.bitkit.models.widget.toNewsModel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetsRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val newsService: NewsService
) {


    suspend fun getNews() : Result<List<NewsModel>> = withContext(bgDispatcher) {
        return@withContext try {
            Result.success(newsService.fetchLatestNews().map { it.toNewsModel() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "WidgetsRepo"
        private const val CACHE_KEY = "news_widget_cache"
        private const val REFRESH_INTERVAL = 2 * 60 * 1000L // 2 minutes in milliseconds
    }
}
