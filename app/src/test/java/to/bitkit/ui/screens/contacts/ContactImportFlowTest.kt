package to.bitkit.ui.screens.contacts

import org.junit.Test
import to.bitkit.models.PubkyProfile
import to.bitkit.ui.Routes
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContactImportFlowTest {
    private companion object {
        const val VALID_PUBLIC_KEY = "pubkyybndrfg8ejkmcpqxot1uwisza345h769ybndrfg8ejkmcpqxot1u"
        const val OTHER_VALID_PUBLIC_KEY = "pubkya345h769ybndrfg8ejkmcpqxot1uwiszybndrfg8ejkmcpqxot1u"
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
    fun `resolveAddContactValidation returns normalized key for valid input`() {
        val rawKey = VALID_PUBLIC_KEY.removePrefix("pubky")

        assertEquals(
            AddContactValidationResult.Valid(normalizedKey = VALID_PUBLIC_KEY),
            resolveAddContactValidation(input = rawKey, ownPublicKey = null),
        )
    }

    @Test
    fun `resolvePastedPubkyRoute returns profile for own key`() {
        assertEquals(
            Routes.Profile,
            resolvePastedPubkyRoute(
                input = VALID_PUBLIC_KEY,
                ownPublicKey = VALID_PUBLIC_KEY,
                contacts = emptyList(),
            ),
        )
    }

    @Test
    fun `resolvePastedPubkyRoute returns contact detail for existing contact`() {
        assertEquals(
            Routes.ContactDetail(VALID_PUBLIC_KEY),
            resolvePastedPubkyRoute(
                input = VALID_PUBLIC_KEY,
                ownPublicKey = OTHER_VALID_PUBLIC_KEY,
                contacts = listOf(PubkyProfile.placeholder(VALID_PUBLIC_KEY)),
            ),
        )
    }

    @Test
    fun `resolvePastedPubkyRoute returns add contact for unknown key`() {
        assertEquals(
            Routes.AddContact(VALID_PUBLIC_KEY),
            resolvePastedPubkyRoute(
                input = VALID_PUBLIC_KEY,
                ownPublicKey = OTHER_VALID_PUBLIC_KEY,
                contacts = emptyList(),
            ),
        )
    }

    @Test
    fun `resolvePastedPubkyRoute returns null for invalid input`() {
        assertNull(
            resolvePastedPubkyRoute(
                input = "not-a-pubky",
                ownPublicKey = null,
                contacts = emptyList(),
            ),
        )
    }
}
