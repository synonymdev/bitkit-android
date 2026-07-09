package to.bitkit.ui.screens.contacts

import org.junit.Test
import to.bitkit.models.PubkyProfile
import kotlin.test.assertEquals

class ContactImportFlowTest {
    private companion object {
        const val VALID_PUBLIC_KEY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
    }

    @Test
    fun `resolveAddContactValidation returns empty for blank input`() {
        assertEquals(
            AddContactValidationResult.Empty,
            resolveAddContactValidation(input = "   ", ownPublicKey = null),
        )
    }

    @Test
    fun `resolveAddContactValidation returns invalid key for bad input`() {
        assertEquals(
            AddContactValidationResult.InvalidKey,
            resolveAddContactValidation(input = "pubkyinvalid", ownPublicKey = null),
        )
    }

    @Test
    fun `resolveAddContactValidation returns own key for self`() {
        assertEquals(
            AddContactValidationResult.OwnKey,
            resolveAddContactValidation(input = VALID_PUBLIC_KEY, ownPublicKey = VALID_PUBLIC_KEY),
        )
    }

    @Test
    fun `resolveAddContactValidation returns existing contact for saved contact`() {
        assertEquals(
            AddContactValidationResult.ExistingContact,
            resolveAddContactValidation(
                input = VALID_PUBLIC_KEY,
                ownPublicKey = null,
                contacts = listOf(PubkyProfile.placeholder(VALID_PUBLIC_KEY)),
            ),
        )
    }

    @Test
    fun `resolveAddContactValidation returns normalized key for valid input`() {
        val rawKey = VALID_PUBLIC_KEY.removePrefix("pubky")

        assertEquals(
            AddContactValidationResult.Valid(normalizedKey = VALID_PUBLIC_KEY),
            resolveAddContactValidation(input = rawKey, ownPublicKey = null),
        )
    }
}
