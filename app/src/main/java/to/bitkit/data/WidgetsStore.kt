package to.bitkit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import to.bitkit.data.dto.ArticleDTO
import to.bitkit.data.serializers.WidgetsSerializer
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store: DataStore<WidgetsData> = DataStoreFactory.create(
        serializer = WidgetsSerializer,
        produceFile = { context.dataStoreFile("settings.json") },
    )

    val data: Flow<WidgetsData> = store.data

    suspend fun update(transform: (WidgetsData) -> WidgetsData) {
        store.updateData(transform)
    }

    suspend fun updateArticles(articles: List<ArticleDTO>) {
        store.updateData {
            it.copy(articles = articles)
        }
    }

    suspend fun reset() {
        store.updateData { WidgetsData() }
        Logger.info("Deleted all user settings data.")
    }
}

@Serializable
data class WidgetsData(
    val articles: List<ArticleDTO> = emptyList(),
)
