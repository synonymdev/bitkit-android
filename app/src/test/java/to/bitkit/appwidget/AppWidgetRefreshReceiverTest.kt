package to.bitkit.appwidget

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.test.BaseUnitTest

@HiltAndroidTest
@Config(application = HiltTestApplication::class, sdk = [34])
@RunWith(RobolectricTestRunner::class)
class AppWidgetRefreshReceiverTest : BaseUnitTest() {
    @get:Rule(order = 1)
    val hiltRule = HiltAndroidRule(this)

    @BindValue
    val appWidgetRefreshScheduler = mock<AppWidgetRefreshScheduler>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val receiver = AppWidgetRefreshReceiver()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun `boot completed delegates to scheduler`() {
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        verify(appWidgetRefreshScheduler).ensureScheduled(AppWidgetRefreshReason.BOOT_COMPLETED)
        verify(appWidgetRefreshScheduler).requestCatchUp(AppWidgetRefreshReason.BOOT_COMPLETED)
    }

    @Test
    fun `package replaced delegates to scheduler`() {
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        verify(appWidgetRefreshScheduler).ensureScheduled(AppWidgetRefreshReason.PACKAGE_REPLACED)
        verify(appWidgetRefreshScheduler).requestCatchUp(AppWidgetRefreshReason.PACKAGE_REPLACED)
    }

    @Test
    fun `catch-up alarm delegates to scheduler`() {
        receiver.onReceive(context, Intent(AppWidgetRefreshScheduler.CATCH_UP_ALARM_ACTION))

        verify(appWidgetRefreshScheduler).handleCatchUpAlarm(AppWidgetRefreshReason.CATCH_UP_ALARM)
    }

}
