package to.bitkit.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import to.bitkit.BuildConfig
import to.bitkit.async.appScope
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Dev-only offset that moves Paykit subscription scheduling forward, so a recorded demo can show a renewal without
 * waiting a whole billing period. It covers subscription proposals, acceptance, due periods, renewal dates and due
 * notifications. One-time payment requests, invoices, payments and allowance checks keep real time.
 */
object DemoClock {
    /** Offsets the Dev Settings accept, in whole days. */
    val OFFSET_DAYS_RANGE = 0..400

    /** Offsets offered in Dev Settings, in whole days. */
    val OFFSET_DAYS_PRESETS = listOf(0, 1, 7, 30, 31, 62, 365)

    val isAvailable: Boolean get() = BuildConfig.DEBUG

    @Volatile
    var offsetDays: Int = 0
        private set

    fun setOffsetDays(days: Int) = run { offsetDays = clampedOffsetDays(days) }

    fun clampedOffsetDays(days: Int): Int = days.coerceIn(OFFSET_DAYS_RANGE)

    fun subscriptionDate(
        date: Instant,
        offsetDays: Int = this.offsetDays,
        isAvailable: Boolean = this.isAvailable,
    ): Instant {
        if (!isAvailable) return date
        val days = clampedOffsetDays(offsetDays)
        if (days == 0) return date
        return date + days.days
    }

    fun subscriptionNow(clock: Clock = Clock.System): Instant = subscriptionDate(clock.now())

    /** [base] moved by the current offset, for code that schedules subscriptions through an injected [Clock]. */
    fun subscriptionClock(base: Clock): Clock = object : Clock {
        override fun now(): Instant = subscriptionDate(base.now())
    }
}

/** Keeps [DemoClock.offsetDays] in step with the offset stored by Dev Settings. */
@Singleton
class DemoClockSync @Inject constructor(
    private val settingsStore: SettingsStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val TAG = "DemoClockSync"
    }

    private val scope = appScope(ioDispatcher, TAG)

    fun start() {
        if (!DemoClock.isAvailable) return
        scope.launch {
            settingsStore.demoClockOffsetDays.collect {
                DemoClock.setOffsetDays(it)
                if (it != 0) Logger.info("Set the demo clock offset to '$it' days", context = TAG)
            }
        }
    }
}
