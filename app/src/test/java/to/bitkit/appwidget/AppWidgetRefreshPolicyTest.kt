package to.bitkit.appwidget

import org.junit.Test
import to.bitkit.appwidget.model.AppWidgetRefreshMetadata
import to.bitkit.appwidget.model.AppWidgetType
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class AppWidgetRefreshPolicyTest {
    @Test
    fun `fresh remote widget type is skipped`() {
        val nowMs = 1_000_000L
        val metadata = AppWidgetRefreshMetadata(
            lastAttemptAtMs = nowMs - 1.minutes.inWholeMilliseconds,
            lastSuccessAtMs = nowMs - 1.minutes.inWholeMilliseconds,
        )

        val result = AppWidgetRefreshPolicy.shouldRefreshRemote(
            AppWidgetType.HEADLINES,
            metadata,
            nowMs,
        )

        assertFalse(result)
    }

    @Test
    fun `stale remote widget type is refreshed`() {
        val nowMs = 1_000_000L
        val metadata = AppWidgetRefreshMetadata(
            lastAttemptAtMs = nowMs - 16.minutes.inWholeMilliseconds,
            lastSuccessAtMs = nowMs - 16.minutes.inWholeMilliseconds,
        )

        val result = AppWidgetRefreshPolicy.shouldRefreshRemote(
            AppWidgetType.WEATHER,
            metadata,
            nowMs,
        )

        assertTrue(result)
    }

    @Test
    fun `facts widget type is never treated as remote backed`() {
        val nowMs = 1_000_000L
        val metadata = AppWidgetRefreshMetadata()

        val result = AppWidgetRefreshPolicy.shouldRefreshRemote(
            AppWidgetType.FACTS,
            metadata,
            nowMs,
        )

        assertFalse(result)
    }
}
