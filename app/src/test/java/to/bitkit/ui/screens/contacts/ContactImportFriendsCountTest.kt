package to.bitkit.ui.screens.contacts

import android.content.res.Configuration
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import to.bitkit.R
import java.util.Locale
import kotlin.test.assertEquals

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class ContactImportFriendsCountTest {
    @Test
    fun `friend count uses each locale's plural form`() {
        val cases = listOf(
            Triple("en", 0, "0 friends"),
            Triple("en", 1, "1 friend"),
            Triple("en", 2, "2 friends"),
            Triple("de", 1, "1 Freund"),
            Triple("cs", 2, "2 přátelé"),
            Triple("cs", 5, "5 přátel"),
            Triple("pl", 2, "2 znajomych"),
            Triple("ru", 1, "1 друг"),
            Triple("ru", 2, "2 друга"),
            Triple("ru", 5, "5 друзей"),
            Triple("ru", 21, "21 друг"),
        )
        cases.forEach { (language, count, expected) ->
            assertEquals(expected, friendsCount(language, count), "$language $count")
        }
    }

    private fun friendsCount(language: String, count: Int): String {
        val app = RuntimeEnvironment.getApplication()
        val configuration = Configuration(app.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        return app.createConfigurationContext(configuration).resources
            .getQuantityString(R.plurals.contacts__import_friends_count, count, count)
    }
}
