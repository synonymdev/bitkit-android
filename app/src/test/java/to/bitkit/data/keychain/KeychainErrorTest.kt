package to.bitkit.data.keychain

import org.junit.Test
import java.io.IOException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class KeychainErrorTest {

    @Test
    fun `FailedToLoad without cause renders key and terminating period`() {
        val error = KeychainError.FailedToLoad(key = "BIP39_MNEMONIC")

        assertEquals("Failed to load BIP39_MNEMONIC from keychain.", error.message)
        assertNull(error.cause)
    }

    @Test
    fun `FailedToLoad preserves cause and embeds its simple class name`() {
        val cause = AEADBadTagException("leak-probe-9f3a")
        val error = KeychainError.FailedToLoad(key = "BIP39_MNEMONIC", cause = cause)

        assertSame(cause, error.cause)
        assertEquals(
            "Failed to load BIP39_MNEMONIC from keychain (cause='AEADBadTagException')",
            error.message,
        )
    }

    @Test
    fun `FailedToLoad does not leak underlying cause message`() {
        val probe = "leak-probe-9f3a"
        val causes = listOf(
            AEADBadTagException(probe),
            BadPaddingException(probe),
            ClassCastException(probe),
            IllegalArgumentException(probe),
            IOException(probe),
        )

        causes.forEach { cause ->
            val error = KeychainError.FailedToLoad(key = "BIP39_MNEMONIC", cause = cause)
            assertFalse(
                actual = error.message.orEmpty().contains(probe),
                message = "message leaked cause.message for ${cause.javaClass.simpleName}",
            )
        }
    }

    @Test
    fun `FailedToLoad exposes the failing key`() {
        val error = KeychainError.FailedToLoad(key = "BIP39_PASSPHRASE", cause = IOException())

        assertEquals("BIP39_PASSPHRASE", error.key)
    }

    @Test
    fun `FailedToSave preserves cause and renders simple class name`() {
        val cause = IOException("probe")
        val error = KeychainError.FailedToSave(key = "BIP39_MNEMONIC", cause = cause)

        assertSame(cause, error.cause)
        assertEquals(
            "Failed to save to BIP39_MNEMONIC keychain (cause='IOException')",
            error.message,
        )
    }

    @Test
    fun `FailedToDelete preserves cause and renders simple class name`() {
        val cause = IOException("probe")
        val error = KeychainError.FailedToDelete(key = "BIP39_MNEMONIC", cause = cause)

        assertSame(cause, error.cause)
        assertEquals(
            "Failed to delete BIP39_MNEMONIC from keychain (cause='IOException')",
            error.message,
        )
    }

    @Test
    fun `FailedToSaveAlreadyExists has no cause and static message`() {
        val error = KeychainError.FailedToSaveAlreadyExists(key = "BIP39_MNEMONIC")

        assertNull(error.cause)
        assertEquals(
            "Key BIP39_MNEMONIC already exists in keychain. " +
                "Explicitly delete key before attempting to update value.",
            error.message,
        )
    }
}
