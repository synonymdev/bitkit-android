package to.bitkit.build

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.R
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FeesChangedStringsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `fees changed descriptions contain the amount placeholder`() {
        listOf(
            R.string.lightning__spending_confirm__fees_changed_service,
            R.string.lightning__spending_confirm__fees_changed_network,
        ).forEach { id ->
            val name = context.resources.getResourceEntryName(id)
            assertTrue(context.getString(id).contains("{amount}"), "Missing {amount} in $name")
        }
    }
}
