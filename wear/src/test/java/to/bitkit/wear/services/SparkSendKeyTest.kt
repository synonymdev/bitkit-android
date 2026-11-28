package to.bitkit.wear.services

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SparkSendKeyTest {
    private companion object {
        /** The BIP39 seed of `abandon … about` with passphrase `TREZOR`, from the BIP39 test vectors. */
        const val SEED = "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e534955" +
            "31f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04"

        /** Bytes 0 to 31. */
        const val PAYMENT_HASH = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    }

    // The phone's SparkSendKeyTest and iOS's SparkSendKeyTests assert the same vector, so the watch, the phone and a
    // wallet restored on the other app all derive the same keys.
    @Test
    fun `derives the shared test vector`() {
        val secret = SparkSendKey.secret(SEED.hexToByteArray())

        assertEquals("577ceb10411255cef07e3f49bd7e5239de07586c7457f65ee72ee53737361a98", secret.toHexString())
        assertEquals("8d92d8bf-76f3-56fe-8958-8e440acb64fc", SparkSendKey.key(secret, PAYMENT_HASH, 0))
        assertEquals("712dea1f-8268-5e52-a280-aac86bc60b59", SparkSendKey.key(secret, PAYMENT_HASH, 1))
    }

    @Test
    fun `reads the payment hash in either case`() {
        val secret = SparkSendKey.secret(SEED.hexToByteArray())

        assertEquals(
            SparkSendKey.key(secret, PAYMENT_HASH, 0),
            SparkSendKey.key(secret, PAYMENT_HASH.uppercase(), 0),
        )
    }

    @Test
    fun `another seed derives other keys`() {
        val other = SparkSendKey.secret(ByteArray(64))

        assertNotEquals("8d92d8bf-76f3-56fe-8958-8e440acb64fc", SparkSendKey.key(other, PAYMENT_HASH, 0))
    }
}
