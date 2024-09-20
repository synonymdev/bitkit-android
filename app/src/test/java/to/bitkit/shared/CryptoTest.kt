package to.bitkit.shared

import org.junit.Before
import org.junit.Test
import to.bitkit.env.Env.DERIVATION_NAME
import to.bitkit.ext.fromBase64
import to.bitkit.ext.hex
import to.bitkit.ext.toBase64
import to.bitkit.fcm.EncryptedNotification
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals


class CryptoTest {
    private lateinit var sut: Crypto

    @Before
    fun setUp() {
        sut = Crypto()
    }

    @Test
    fun `it should generate valid shared secret from keypair`() {
        val (privateKey, publicKey) = sut.generateKeyPair()

        val sharedSecret = sut.generateSharedSecret(privateKey, publicKey.hex)
        assertEquals(32, privateKey.size)
        assertEquals(33, publicKey.size)
        assertEquals(33, sharedSecret.size)

        val sharedSecretHash = sut.generateSharedSecret(privateKey, publicKey.hex, DERIVATION_NAME)
        assertEquals(32, sharedSecretHash.size)
    }

    @Test
    fun `it should decrypt payload it encrypted`() {
        val derivationName = DERIVATION_NAME

        // Step 1: Client generates a key pair
        val clientKeys = sut.generateKeyPair()
        val clientPublicKey = clientKeys.publicKey
        val clientPrivateKey = clientKeys.privateKey

        // Step 2: Server generates a key pair
        val serverKeys = sut.generateKeyPair()
        val serverPublicKey = serverKeys.publicKey
        val serverPrivateKey = serverKeys.privateKey

        // Step 3: Server generates shared secret using its private key and client public key
        val serverSecret = sut.generateSharedSecret(serverPrivateKey, clientPublicKey.hex, derivationName)

        // Step 4: Server encrypts data using the shared secret
        val dataToEncrypt = "Hello from the server!"
        val encrypted = sut.encrypt(dataToEncrypt.toByteArray(), serverSecret)
        val response = EncryptedNotification(
            cipher = encrypted.cipher.toBase64(),
            iv = encrypted.iv.hex,
            tag = encrypted.tag.hex,
            publicKey = serverPublicKey.hex,
        )

        // Step 5: Client generates its shared secret using its private key and server public key
        val clientSecret = sut.generateSharedSecret(clientPrivateKey, response.publicKey, derivationName)

        // TODO: delete me
        println("secret eq: " + (clientSecret contentEquals serverSecret))
        val server = object {
            val cipher = encrypted.cipher
            val iv = encrypted.iv
            val tag = encrypted.tag
        }
        val client = object {
            val cipher = response.cipher.fromBase64()
            val iv = response.iv.hex
            val tag = response.tag.hex
        }
        println("cipher eq: " + (server.cipher contentEquals client.cipher))
        println("iv eq: " + (server.iv contentEquals client.iv))
        println("tag eq: " + (server.tag contentEquals client.tag))

        // Step 6: Client decrypts the payload using the shared secret
        val decrypted = sut.decrypt(
            encryptedPayload = Crypto.EncryptedPayload(
                cipher = response.cipher.fromBase64(),
                iv = response.iv.hex,
                tag = response.tag.hex,
            ),
            secretKey = clientSecret,
        )
        val decoded = decrypted.decodeToString()

        assertContentEquals(clientSecret, serverSecret)
        assertEquals(dataToEncrypt, decoded)
    }

    @OptIn(ExperimentalUnsignedTypes::class, ExperimentalStdlibApi::class)
    @Test
    @Suppress("SpellCheckingInspection")
    fun testBlocktankEncryptedPayload() {
        val clientPrivateKey = "cc74b1a4fdcd35916c766d3318c5a93b7e33a36ebeff0463128bf284975c2680"
        val serverPublicKey = "031e9923e689a181a803486b7d8c0d4a5aad360edb70c8bb413a98458d91652213"
        val derivationName = "bitkit-notifications"

        val ciphertext = "l2fInfyw64gO12odo8iipISloQJ45Rc4WjFmpe95brdaAMDq+T/L9ZChcmMCXnR0J6BXd8sSIJe/0bmby8uSZZJuVCzwF76XHfY5oq0Y1/hKzyZTn8nG3dqfiLHnAPy1tZFQfm5ALgjwWnViYJLXoGFpXs7kLMA=".fromBase64()
        val iv = "2b8ed77fd2198e3ed88cfaa794a246e8"
        val tag = "caddd13746d6a6aed16176734964d3a3"
        val decryptedPayload = """{"source":"blocktank","type":"incomingHtlc","payload":{"secretMessage":"hello"},"createdAt":"2024-09-18T13:33:52.555Z"}"""

        // Without derivationName
        val sharedSecret = sut.generateSharedSecret(clientPrivateKey.hex, serverPublicKey)
        val sharedSecretOnServer = "028ce542975d6d7b2307c92e527d507b03ffb3d897eb2e0830d29f40d5efd80ee3".hex
        assertEquals(sharedSecretOnServer.hex, sharedSecret.hex)
        // assertEquals(expected1.sliceArray(1 until expected1.size).hex, sharedSecret.hex)

        val sharedHash = sut.generateSharedSecret(clientPrivateKey.hex, serverPublicKey, derivationName)
        val sharedHashOnServer = "3a9d552cb16dfae40feae644254c4ca46cab82e570de5662aacc4018e33b609b".hex
        assertEquals(sharedHashOnServer.hex, sharedHash.hex)

        val encryptedPayload = Crypto.EncryptedPayload(cipher = ciphertext, iv = iv.hex, tag = tag.hex)

        val value = sut.decrypt(encryptedPayload, secretKey = sharedHash)

        assertEquals(decryptedPayload, String(value))
    }
}
