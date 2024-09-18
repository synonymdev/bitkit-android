package to.bitkit.shared

import org.junit.Assert.*

import org.junit.Before
import org.junit.Test
import to.bitkit.ext.hex

class CryptoTest {
    private lateinit var sut: Crypto

    @Before
    fun setUp() {
        sut = Crypto()
    }

    @Test
    fun `it should generate valid secp256k1 shared secret from private and public key`() {
        val (privateKey, publicKey) = sut.generateKeyPair()

        val sharedSecret = sut.generateSharedSecret(privateKey, publicKey.hex)

        // println("Private Key(${privateKey.size}): ${privateKey.hex}")
        // println("Public Key(${publicKey.size}): ${publicKey.hex}")
        // println("Shared secret(${sharedSecret.size}): ${sharedSecret.hex}")
        assertEquals(32, privateKey.size)
        assertEquals(33, publicKey.size)
        assertEquals(32, sharedSecret.size)
    }
}
