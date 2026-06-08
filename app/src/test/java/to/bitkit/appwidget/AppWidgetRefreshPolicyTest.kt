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
        val nowMs = 10_000_000L
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
        val nowMs = 10_000_000L
        val metadata = AppWidgetRefreshMetadata(
            lastAttemptAtMs = nowMs - 16.minutes.inWholeMilliseconds,
            lastSuccessAtMs = nowMs - 16.minutes.inWholeMilliseconds,
        )

        val result = AppWidgetRefreshPolicy.shouldRefreshRemote(
            AppWidgetType.BLOCKS,
            metadata,
            nowMs,
        )

        assertTrue(result)
    }

    @Test
    fun `recent failed remote widget attempt is skipped`() {
        val nowMs = 10_000_000L
        val metadata = AppWidgetRefreshMetadata(
            lastAttemptAtMs = nowMs - 1.minutes.inWholeMilliseconds,
            lastSuccessAtMs = nowMs - 16.minutes.inWholeMilliseconds,
        )

        val result = AppWidgetRefreshPolicy.shouldRefreshRemote(
            AppWidgetType.WEATHER,
            metadata,
            nowMs,
        )

        assertFalse(result)
    }

    @Test
    fun `recent failed remote widget attempt is refreshed when backoff is bypassed`() {
        val nowMs = 10_000_000L
        val metadata = AppWidgetRefreshMetadata(
            lastAttemptAtMs = nowMs - 1.minutes.inWholeMilliseconds,
            lastSuccessAtMs = nowMs - 16.minutes.inWholeMilliseconds,
        )

        val result = AppWidgetRefreshPolicy.shouldRefreshRemote(
            type = AppWidgetType.BLOCKS,
            metadata = metadata,
            nowMs = nowMs,
            bypassFailedAttemptBackoff = true,
        )

        assertTrue(result)
    }

    @Test
    fun `uncached remote data still honors recent failed attempt backoff`() {
        val nowMs = 10_000_000L
        val metadata = AppWidgetRefreshMetadata(
            lastAttemptAtMs = nowMs - 1.minutes.inWholeMilliseconds,
            lastSuccessAtMs = nowMs - 16.minutes.inWholeMilliseconds,
        )

        val result = AppWidgetRefreshPolicy.shouldRefreshRemote(
            type = AppWidgetType.PRICE,
            metadata = metadata,
            nowMs = nowMs,
            hasUncachedData = true,
        )

        assertFalse(result)
    }

    @Test
    fun `weather widget uses longer refresh interval`() {
        val nowMs = 10_000_000L
        val metadata = AppWidgetRefreshMetadata(
            lastAttemptAtMs = nowMs - 16.minutes.inWholeMilliseconds,
            lastSuccessAtMs = nowMs - 16.minutes.inWholeMilliseconds,
        )

        val result = AppWidgetRefreshPolicy.shouldRefreshRemote(
            AppWidgetType.WEATHER,
            metadata,
            nowMs,
        )

        assertFalse(result)
    }

    @Test
    fun `headlines widget uses longer refresh interval`() {
        val nowMs = 10_000_000L
        val metadata = AppWidgetRefreshMetadata(
            lastAttemptAtMs = nowMs - 31.minutes.inWholeMilliseconds,
            lastSuccessAtMs = nowMs - 31.minutes.inWholeMilliseconds,
        )

        val result = AppWidgetRefreshPolicy.shouldRefreshRemote(
            AppWidgetType.HEADLINES,
            metadata,
            nowMs,
        )

        assertFalse(result)
    }

    @Test
    fun `headlines widget refreshes after longer interval`() {
        val nowMs = 10_000_000L
        val metadata = AppWidgetRefreshMetadata(
            lastAttemptAtMs = nowMs - 61.minutes.inWholeMilliseconds,
            lastSuccessAtMs = nowMs - 61.minutes.inWholeMilliseconds,
        )

        val result = AppWidgetRefreshPolicy.shouldRefreshRemote(
            AppWidgetType.HEADLINES,
            metadata,
            nowMs,
        )

        assertTrue(result)
    }

    @Test
    fun `facts widget type is never treated as remote backed`() {
        val nowMs = 10_000_000L
        val metadata = AppWidgetRefreshMetadata()

        val result = AppWidgetRefreshPolicy.shouldRefreshRemote(
            AppWidgetType.FACTS,
            metadata,
            nowMs,
        )

        assertFalse(result)
    }
}
