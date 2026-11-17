package to.bitkit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import to.bitkit.data.serializers.WakeStoreSerializer
import javax.inject.Inject
import javax.inject.Singleton

private val Context.wakeDataStore: DataStore<WakeStoreData> by dataStore(
    fileName = "wake.json",
    serializer = WakeStoreSerializer,
)

/**
 * The wakes this install received and the gateway's topic texts. Like the wake credentials it belongs to the install,
 * so it stays out of the backed up app cache.
 */
@Singleton
class WakeStore internal constructor(
    private val store: DataStore<WakeStoreData>,
) {
    companion object {
        /** Most wakes remembered at once. Each is forgotten at its deadline long before this fills. */
        const val MAX_RECEIVED = 200
    }

    @Inject
    constructor(@ApplicationContext context: Context) : this(context.wakeDataStore)

    val data: Flow<WakeStoreData> = store.data

    suspend fun received(id: String): ReceivedWake? = store.data.first().received.find { it.id == id }

    /** Remembers [wake], and forgets the wakes whose deadline passed before [nowSecs]. */
    suspend fun saveReceived(wake: ReceivedWake, nowSecs: Long) {
        store.updateData { data ->
            val kept = data.received.filter { it.id != wake.id && it.deadlineSecs >= nowSecs }
            data.copy(received = (kept + wake).takeLast(MAX_RECEIVED))
        }
    }

    suspend fun markAcked(id: String) {
        store.updateData { data ->
            data.copy(received = data.received.map { if (it.id == id) it.copy(acked = true) else it })
        }
    }

    suspend fun saveTopics(topics: List<WakeTopicText>, fetchedAtMs: Long) {
        store.updateData { it.copy(topics = topics, topicsFetchedAtMs = fetchedAtMs) }
    }
}

@Serializable
data class WakeStoreData(
    val received: List<ReceivedWake> = emptyList(),
    val topics: List<WakeTopicText> = emptyList(),
    val topicsFetchedAtMs: Long = 0L,
)

/**
 * A wake this install received, kept until its deadline so a second delivery acknowledges it without doing its work
 * again. Delivery is at least once, and the process can die between two deliveries.
 */
@Serializable
data class ReceivedWake(
    val id: String,
    val deadlineSecs: Long,
    /** The name of the `WakeAckOutcome` to acknowledge. */
    val outcome: String,
    val acked: Boolean = false,
)

/** A topic's display text from the gateway, for the alert Android has to post itself. */
@Serializable
data class WakeTopicText(
    val name: String,
    val title: String? = null,
    val body: String? = null,
)
