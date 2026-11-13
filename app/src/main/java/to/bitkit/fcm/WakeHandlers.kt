package to.bitkit.fcm

import android.content.Context
import com.synonym.bitkitcore.WakeAckOutcome
import com.synonym.bitkitcore.WakeEnvelope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import to.bitkit.data.WakeStore
import to.bitkit.data.WakeTopicText
import to.bitkit.ext.nowMs
import to.bitkit.ext.runSuspendCatching
import to.bitkit.repositories.WakeConfig
import to.bitkit.services.WakeService
import to.bitkit.ui.ID_NOTIFICATION_SKIPPED
import to.bitkit.ui.pushNotification
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

/** Does the work of a wake of one topic. Returns once the work is done, and throws when it failed. */
fun interface WakeHandler {
    suspend fun handle(wake: WakeEnvelope)
}

/**
 * The handler of each topic the app handles. A wake of any other topic is acknowledged `no_handler`, which leaves the
 * topic's fallback alert to fire. Swap claims (`swap.claimable`) and Paykit syncs (`paykit.sync`) get theirs here.
 */
@Singleton
class WakeHandlers internal constructor(private val byTopic: Map<String, WakeHandler>) {
    companion object {
        private const val TAG = "WakeHandlers"

        /** The gateway's self-test, which it sends only to the device that asked for it. */
        const val TEST_TOPIC = "wake.test"
    }

    @Inject
    constructor(alertHandler: WakeAlertHandler) : this(mapOf(TEST_TOPIC to alertHandler))

    /** Runs the handler of the wake's topic, and maps what became of it to the outcome to acknowledge. */
    suspend fun handle(wake: WakeEnvelope): WakeAckOutcome {
        val handler = byTopic[wake.topic] ?: return WakeAckOutcome.NO_HANDLER
        return runSuspendCatching { handler.handle(wake) }.fold(
            onSuccess = { WakeAckOutcome.HANDLED },
            onFailure = {
                Logger.warn("Failed to handle wake of topic '${wake.topic}'", it, context = TAG)
                WakeAckOutcome.FAILED
            },
        )
    }
}

/**
 * Posts the topic's display text as a local notification. A v1 wake is always a data message, which Android shows
 * nothing for, so this is how an alert topic reaches the user.
 */
class WakeAlertHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val topicTexts: WakeTopicTexts,
) : WakeHandler {
    override suspend fun handle(wake: WakeEnvelope) {
        val wakeId = checkNotNull(wake.id) { "A wake without an id" }
        val text = checkNotNull(topicTexts.find(wake.topic)) { "No display text for topic '${wake.topic}'" }
        val id = context.pushNotification(title = text.title, text = text.body, id = wakeNotificationId(wakeId))
        check(id != ID_NOTIFICATION_SKIPPED) { "Notification permission not granted" }
    }
}

/**
 * The notification id of a wake's alert: the same on every delivery of the wake, so a retry replaces the alert instead
 * of adding another, and at least 2^30, clear of the app's fixed ids and [ID_NOTIFICATION_SKIPPED].
 */
internal fun wakeNotificationId(wakeId: String): Int = (wakeId.hashCode() or (1 shl 30)) and Int.MAX_VALUE

/** The gateway's display text of each topic, cached and refreshed now and then. */
@OptIn(ExperimentalTime::class)
@Singleton
class WakeTopicTexts @Inject constructor(
    private val wakeService: WakeService,
    private val wakeConfig: WakeConfig,
    private val wakeStore: WakeStore,
    private val clock: Clock,
) {
    companion object {
        private const val TAG = "WakeTopicTexts"

        /** How long cached texts are used before they are fetched again. */
        val MAX_AGE = 1.days
    }

    /** The display text of [topic], or null when the gateway has none for it. */
    suspend fun find(topic: String): WakeTopicText? {
        val cache = wakeStore.data.first()
        val cached = cache.topics.find { it.name == topic && it.hasText() }
        val isFresh = clock.nowMs() - cache.topicsFetchedAtMs < MAX_AGE.inWholeMilliseconds
        if (cached != null && isFresh) return cached
        return refresh()?.find { it.name == topic && it.hasText() } ?: cached
    }

    private suspend fun refresh(): List<WakeTopicText>? = runSuspendCatching {
        val gatewayUrl = checkNotNull(wakeConfig.gatewayUrl) { "No wake gateway in this build" }
        val texts = wakeService.listTopics(gatewayUrl).map {
            WakeTopicText(name = it.name, title = it.title, body = it.body)
        }
        wakeStore.saveTopics(texts, fetchedAtMs = clock.nowMs())
        texts
    }.onFailure { Logger.warn("Failed to refresh wake topic texts", it, context = TAG) }.getOrNull()

    private fun WakeTopicText.hasText(): Boolean = title != null && body != null
}
