package to.bitkit.repositories

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class SparkExitWorkTest : BaseUnitTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sut = SparkExitWork(context, testDispatcher, mock(), mock())
    private val posted get() = context.getSystemService(NotificationManager::class.java).activeNotifications

    @Test
    fun `a loss posted in the tick that clears the fee ask stays posted`() {
        sut.notify(SparkExitAlert.Funding)
        sut.notify(SparkExitAlert.Lost, amountSats = 8_192uL)

        sut.dismissFundingAsk()

        assertEquals(1, posted.size, "the loss alert was taken back with the fee ask")
    }

    @Test
    fun `a fee ask the exit no longer has is taken back`() {
        sut.notify(SparkExitAlert.Funding)

        sut.dismissFundingAsk()

        assertEquals(0, posted.size)
    }
}
