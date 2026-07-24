package to.bitkit.data.sharing

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SharedPubkyContractTest {
    companion object {
        private const val WIRE_PUBKY = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val SECRET_KEY = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    }

    @Test
    fun `wire format is always bare lowercase z-base32`() {
        assertEquals(WIRE_PUBKY, SharedPubkyContract.canonicalPubky(" PUBKY${WIRE_PUBKY.uppercase()} "))
        assertEquals("pubky$WIRE_PUBKY", SharedPubkyContract.toBitkitPubky(WIRE_PUBKY))
    }

    @Test
    fun `bare wire key may itself begin with pubky`() {
        val wirePubky = "pubky" + "y".repeat(47)

        assertEquals(wirePubky, SharedPubkyContract.canonicalPubky(wirePubky))
        assertEquals("pubky$wirePubky", SharedPubkyContract.toBitkitPubky(wirePubky))
    }

    @Test
    fun `wire format rejects wrong length and alphabet`() {
        assertFailsWith<IllegalArgumentException> {
            SharedPubkyContract.canonicalPubky(WIRE_PUBKY.dropLast(1))
        }
        assertFailsWith<IllegalArgumentException> {
            SharedPubkyContract.canonicalPubky("${WIRE_PUBKY.dropLast(1)}0")
        }
        assertFailsWith<IllegalArgumentException> {
            SharedPubkyContract.requireWirePubky("pubky$WIRE_PUBKY")
        }
        assertFailsWith<IllegalArgumentException> {
            SharedPubkyContract.requireWirePubky(WIRE_PUBKY.uppercase())
        }
    }

    @Test
    fun `credential Uri encodes the selected bare pubky in the v1 path`() {
        assertEquals(
            "content://app.pubkyring.sharedpubky/v1/identities/$WIRE_PUBKY/credential",
            SharedPubkyContract.ringCredentialUriString("pubky$WIRE_PUBKY"),
        )
    }

    @Test
    fun `secret key wire format requires exactly 64 lowercase hex characters`() {
        assertEquals(SECRET_KEY, SharedPubkyContract.canonicalSecretKeyHex(SECRET_KEY))
        assertFailsWith<IllegalArgumentException> {
            SharedPubkyContract.canonicalSecretKeyHex(SECRET_KEY.dropLast(1))
        }
        assertFailsWith<IllegalArgumentException> {
            SharedPubkyContract.canonicalSecretKeyHex("${SECRET_KEY.dropLast(1)}z")
        }
        assertFailsWith<IllegalArgumentException> {
            SharedPubkyContract.canonicalSecretKeyHex(SECRET_KEY.uppercase())
        }
    }

    @Test
    fun `external reference rejects unsupported sources and versions`() {
        assertFailsWith<SharedPubkyError.UnsupportedVersion> {
            ExternalPubkyIdentityRef(
                protocolVersion = 2,
                sourcePackage = SharedPubkyContract.RING_SOURCE,
                pubky = WIRE_PUBKY,
            ).validated()
        }
        assertFailsWith<SharedPubkyError.UntrustedSource> {
            ExternalPubkyIdentityRef(
                protocolVersion = SharedPubkyContract.PROTOCOL_VERSION,
                sourcePackage = "other.app",
                pubky = WIRE_PUBKY,
            ).validated()
        }
    }
}
