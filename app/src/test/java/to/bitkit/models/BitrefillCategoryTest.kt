package to.bitkit.models

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Config(sdk = [34], qualifiers = "en-rUS")
@RunWith(RobolectricTestRunner::class)
class BitrefillCategoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `every entry points at the string resource named after it`() {
        BitrefillCategory.entries.forEach { category ->
            val expected = "other__shop__categories__${category.name.lowercase()}"
            val actual = context.resources.getResourceEntryName(category.titleRes)
            assertEquals(expected, actual, "'${category.name}' resolves to the wrong string resource")
        }
    }

    @Test
    fun `every title resolves to a distinct non-blank label`() {
        val titles = BitrefillCategory.entries.associateWith { context.getString(it.titleRes) }

        titles.forEach { (category, title) ->
            assertTrue(title.isNotBlank(), "'${category.name}' resolves to a blank label")
        }
        assertEquals(
            BitrefillCategory.entries.size,
            titles.values.toSet().size,
            "categories share a label: ${titles.values.groupBy { it }.filterValues { it.size > 1 }.keys}",
        )
    }

    @Test
    fun `every route is a distinct buy path`() {
        val routes = BitrefillCategory.entries.map { it.route }

        routes.forEach { route ->
            assertTrue(route.startsWith("buy/"), "route '$route' is not a buy path")
            assertTrue(route.removePrefix("buy/").isNotBlank(), "route '$route' has no slug")
        }
        assertEquals(BitrefillCategory.entries.size, routes.toSet().size, "categories share a route")
    }
}
