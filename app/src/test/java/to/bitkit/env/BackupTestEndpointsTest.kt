package to.bitkit.env

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BackupTestEndpointsTest {
    private val vss = "http://localhost:18657/vss"
    private val auth = "http://localhost:18655/auth"

    @Test
    fun `disabled fixture ignores even incomplete configuration`() {
        assertNull(BackupTestEndpoints.resolve(false, vss, null))
    }

    @Test
    fun `empty configuration preserves normal endpoints`() {
        assertNull(BackupTestEndpoints.resolve(true, null, " "))
    }

    @Test
    fun `valid explicit pair is trimmed and retained`() {
        assertEquals(BackupTestEndpoints(vss, auth), BackupTestEndpoints.resolve(true, " $vss ", auth))
    }

    @Test
    fun `invalid or partial pair never mixes fixture and normal auth`() {
        listOf(
            null,
            "",
            "https://example.com/auth",
            "http://127.0.0.1:18655/auth",
            "http://localhost/auth",
            "http://user@localhost:18655/auth",
            "http://localhost:18655/auth?token=x",
            "http://localhost:18655/auth#fragment"
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { BackupTestEndpoints.resolve(true, vss, invalid) }
            assertFailsWith<IllegalArgumentException> { BackupTestEndpoints.resolve(true, invalid, auth) }
        }
    }
}
