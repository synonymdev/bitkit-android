package to.bitkit.data.serializers

import org.junit.Test
import to.bitkit.data.sharing.SharedPubkyIdentity
import to.bitkit.di.json
import to.bitkit.test.BaseUnitTest
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PubkyStoreSerializerTest : BaseUnitTest() {
    companion object {
        private const val WIRE_PUBKY = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
    }

    @Test
    fun `persisted reference retains its existing JSON fields and metadata`() = test {
        val storedJson = """
            {
                "cachedName": "Ring user",
                "cachedImageUri": "pubky://avatar",
                "contactProfileOverrides": {},
                "externalIdentityRef": {
                    "protocolVersion": 1,
                    "sourcePackage": "app.pubkyring",
                    "pubky": "$WIRE_PUBKY"
                }
            }
        """.trimIndent()

        val restored = PubkyStoreSerializer.readFrom(storedJson.byteInputStream())
        val output = ByteArrayOutputStream()
        PubkyStoreSerializer.writeTo(restored, output)

        assertEquals(SharedPubkyIdentity(1, "app.pubkyring", WIRE_PUBKY), restored.externalIdentityRef)
        assertEquals(json.parseToJsonElement(storedJson), json.parseToJsonElement(output.toByteArray().decodeToString()))
    }

    @Test
    fun `invalid persisted references survive decoding for explicit recovery`() = test {
        val invalidReferences = listOf(
            SharedPubkyIdentity(2, "app.pubkyring", WIRE_PUBKY),
            SharedPubkyIdentity(1, "other.app", WIRE_PUBKY),
            SharedPubkyIdentity(1, "app.pubkyring", "invalid"),
        )
        for (identity in invalidReferences) {
            val storedJson = """
                {
                    "cachedName": "Preserved metadata",
                    "externalIdentityRef": {
                        "protocolVersion": ${identity.protocolVersion},
                        "sourcePackage": "${identity.sourcePackage}",
                        "pubky": "${identity.pubky}"
                    }
                }
            """.trimIndent()

            val restored = PubkyStoreSerializer.readFrom(storedJson.byteInputStream())

            assertNotNull(restored.externalIdentityRef)
            assertEquals(identity, restored.externalIdentityRef)
            assertEquals("Preserved metadata", restored.cachedName)
        }
    }
}
