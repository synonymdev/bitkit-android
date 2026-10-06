package to.bitkit.utils

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import to.bitkit.env.Env.derivationName
import to.bitkit.ext.fromBase64
import to.bitkit.ext.fromHex
import to.bitkit.ext.toBase64
import to.bitkit.ext.toHex
import to.bitkit.fcm.EncryptedNotification
import java.security.Provider
import java.security.Security
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CryptoTest {
    private companion object {
        const val BC = BouncyCastleProvider.PROVIDER_NAME
    }

    private lateinit var sut: Crypto
    private var baselineProvider: Provider? = null
    private var baselinePosition = 0

    @Before
    fun setUp() {
        baselineProvider = Security.getProvider(BC)
        baselinePosition = positionOf(baselineProvider)
        sut = Crypto()
    }

    @After
    fun tearDown() {
        // The provider list is shared by the whole JVM, so restore BC as it was before this test started
        Security.removeProvider(BC)
        baselineProvider?.let { Security.insertProviderAt(it, baselinePosition) }
    }

    @Test
    fun `it should generate valid shared secret from keypair`() {
        val (privateKey, publicKey) = sut.generateKeyPair()
        assertEquals(32, privateKey.size)
        assertEquals(33, publicKey.size)

        val sharedSecret = sut.generateSharedSecret(privateKey, publicKey.toHex())
        assertEquals(33, sharedSecret.size)

        val sharedSecretHash = sut.generateSharedSecret(privateKey, publicKey.toHex(), derivationName)
        assertEquals(32, sharedSecretHash.size)
    }

    @Test
    fun `it should decrypt payload it encrypted`() {
        val derivationName = derivationName

        // Step 1: Client generates a key pair
        val clientKeys = sut.generateKeyPair()
        val clientPublicKey = clientKeys.publicKey
        val clientPrivateKey = clientKeys.privateKey

        // Step 2: Server generates a key pair
        val serverKeys = sut.generateKeyPair()
        val serverPublicKey = serverKeys.publicKey
        val serverPrivateKey = serverKeys.privateKey

        // Step 3: Server generates shared secret using its private key and client public key
        val serverSecret = sut.generateSharedSecret(serverPrivateKey, clientPublicKey.toHex(), derivationName)

        // Step 4: Server encrypts data using the shared secret
        val dataToEncrypt = "Hello from the server!"
        val encrypted = sut.encrypt(dataToEncrypt.toByteArray(), serverSecret)
        val response = EncryptedNotification(
            cipher = encrypted.cipher.toBase64(),
            iv = encrypted.iv.toHex(),
            tag = encrypted.tag.toHex(),
            publicKey = serverPublicKey.toHex(),
        )

        // Step 5: Client generates its shared secret using its private key and server public key
        val clientSecret = sut.generateSharedSecret(clientPrivateKey, response.publicKey, derivationName)

        // Step 6: Client decrypts the payload using the shared secret
        val decrypted = sut.decrypt(
            encryptedPayload = Crypto.EncryptedPayload(
                cipher = response.cipher.fromBase64(),
                iv = response.iv.fromHex(),
                tag = response.tag.fromHex(),
            ),
            secretKey = clientSecret,
        )
        val decoded = decrypted.decodeToString()

        assertContentEquals(clientSecret, serverSecret)
        assertEquals(dataToEncrypt, decoded)
    }

    @Test
    @Suppress("SpellCheckingInspection")
    fun testBlocktankEncryptedPayload() {
        val clientPrivateKey = "cc74b1a4fdcd35916c766d3318c5a93b7e33a36ebeff0463128bf284975c2680"
        val serverPublicKey = "031e9923e689a181a803486b7d8c0d4a5aad360edb70c8bb413a98458d91652213"
        val derivationName = "bitkit-notifications"

        @Suppress("MaxLineLength")
        val ciphertext = ("l2fInfyw64gO12odo8iipISloQJ45Rc4WjFmpe95brdaAMDq+T/L9ZChcmMCXnR0J6BXd8sSIJe/0bmby8uSZZJuVCzwF76XHfY5oq0Y1/hKzyZTn8nG3dqfiLHnAPy1tZFQfm5ALgjwWnViYJLXoGFpXs7kLMA=").fromBase64()
        val iv = "2b8ed77fd2198e3ed88cfaa794a246e8"
        val tag = "caddd13746d6a6aed16176734964d3a3"

        @Suppress("MaxLineLength")
        val decryptedPayload = """{"source":"blocktank","type":"incomingHtlc","payload":{"secretMessage":"hello"},"createdAt":"2024-09-18T13:33:52.555Z"}"""

        // Without derivationName
        val sharedSecret = sut.generateSharedSecret(clientPrivateKey.fromHex(), serverPublicKey)
        val sharedSecretOnServer = "028ce542975d6d7b2307c92e527d507b03ffb3d897eb2e0830d29f40d5efd80ee3".fromHex()
        assertEquals(sharedSecretOnServer.toHex(), sharedSecret.toHex())

        val sharedHash = sut.generateSharedSecret(clientPrivateKey.fromHex(), serverPublicKey, derivationName)
        val sharedHashOnServer = "3a9d552cb16dfae40feae644254c4ca46cab82e570de5662aacc4018e33b609b".fromHex()
        assertEquals(sharedHashOnServer.toHex(), sharedHash.toHex())

        val encryptedPayload = Crypto.EncryptedPayload(ciphertext, iv.fromHex(), tag.fromHex())

        val value = sut.decrypt(encryptedPayload, sharedHash)

        assertEquals(decryptedPayload, value.decodeToString())
    }

    @Test
    fun `installSecurityProvider adds BouncyCastle when no BC provider is registered`() {
        Security.removeProvider(BC)

        Crypto.installSecurityProvider()

        assertIs<BouncyCastleProvider>(Security.getProvider(BC))
    }

    @Test
    fun `installSecurityProvider replaces an outdated BC provider at position 1`() {
        val outdated = OutdatedBcProvider()
        Security.removeProvider(BC)
        Security.addProvider(outdated)

        Crypto.installSecurityProvider()

        val installed = assertIs<BouncyCastleProvider>(Security.getProviders().first())
        assertSame(installed, Security.getProvider(BC))
        assertTrue(Security.getProviders().none { it === outdated })
    }

    @Test
    fun `installSecurityProvider keeps the installed provider on later calls`() {
        Security.removeProvider(BC)
        Security.addProvider(OutdatedBcProvider())
        Crypto.installSecurityProvider()
        val installed = Security.getProviders().toList()

        Crypto.installSecurityProvider()
        Crypto()

        assertSameProviders(installed, Security.getProviders().toList())
    }

    private fun positionOf(provider: Provider?) = Security.getProviders().indexOfFirst { it === provider } + 1

    private fun assertSameProviders(expected: List<Provider>, actual: List<Provider>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (want, got) -> assertSame(want, got) }
    }

    private class OutdatedBcProvider : Provider(BC, 1.0, "Stub for the BC provider that Android registers")
}
