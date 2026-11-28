package to.bitkit.wear.utils

import org.junit.Test
import to.bitkit.wear.security.SeedStoreError
import kotlin.test.assertEquals

class LoggerTest {
    private val error = SeedStoreError.Failed(IllegalStateException("third-party text"))

    @Test
    fun `a release build logs only the class of an error`() {
        assertEquals(": Failed", Logger.describe(error, hasMessage = false))
    }

    @Test
    fun `a debug build also logs the message of an error`() {
        assertEquals(": Failed: The seed key operation failed", Logger.describe(error, hasMessage = true))
    }
}
