package to.bitkit.viewmodels

import org.junit.Test
import to.bitkit.models.PubkyProfile
import to.bitkit.ui.Routes
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PubkyRouteResolverTest {
    companion object {
        private const val VALID_PUBLIC_KEY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val OTHER_VALID_PUBLIC_KEY = "pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
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
