package to.bitkit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import to.bitkit.data.dto.ArticleDTO
import to.bitkit.data.serializers.WidgetsSerializer
import to.bitkit.models.WidgetType
import to.bitkit.models.WidgetWithPosition
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store: DataStore<WidgetsData> = DataStoreFactory.create(
        serializer = WidgetsSerializer,
        produceFile = { context.dataStoreFile("widgets.json") },
    )

    val data: Flow<WidgetsData> = store.data
    val articlesFlow: Flow<List<ArticleDTO>> = data.map { it.articles }

    suspend fun updateArticles(articles: List<ArticleDTO>) {
        store.updateData {
            it.copy(articles = articles)
        }
    }

    suspend  fun updateHeadlinePreferences(preferences: HeadlinePreferences) {
        store.updateData {
            it.copy(headlinePreferences = preferences)
        }
    }

    suspend fun reset() {
        store.updateData { WidgetsData() }
        Logger.info("Deleted all widgets data.")
    }

    suspend fun addWidget(type: WidgetType) {
        if(store.data.first().widgets.map { it.type }.contains(type)) return

        store.updateData {
            it.copy(widgets = it.widgets + WidgetWithPosition(type = type))
        }
    }

    suspend fun deleteWidget(type: WidgetType) {
        if(!store.data.first().widgets.map { it.type }.contains(type)) return

        store.updateData {
            it.copy(widgets = it.widgets.filterNot { it.type == type })
        }
    }

    suspend fun updateWidgets(widgets: List<WidgetWithPosition>) {
        store.updateData {
            it.copy(widgets = widgets)
        }
    }
}

@Serializable
data class WidgetsData(
    val widgets: List<WidgetWithPosition> = emptyList(),
    val articles: List<ArticleDTO> = emptyList(),
    val headlinePreferences: HeadlinePreferences = HeadlinePreferences()
)
