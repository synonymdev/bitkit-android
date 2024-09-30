package to.bitkit.shared

import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.interfaces.ECPrivateKey
import org.bouncycastle.jce.interfaces.ECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPrivateKeySpec
import org.bouncycastle.jce.spec.ECPublicKeySpec
import to.bitkit.ext.fromHex
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Crypto @Inject constructor() {
    @Suppress("ArrayInDataClass")
    data class KeyPair(
        val privateKey: ByteArray,
        val publicKey: ByteArray,
    )

    @Suppress("ArrayInDataClass")
    data class EncryptedPayload(
        val cipher: ByteArray,
        val iv: ByteArray,
        val tag: ByteArray,
    )

    private val params = ECNamedCurveTable.getParameterSpec("secp256k1")
    private val transformation = "AES/GCM/NoPadding"

    init {
        val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        when {
            provider == null -> Security.addProvider(BouncyCastleProvider())
            provider::class.java != BouncyCastleProvider::class.java -> {
                // We substitute the outdated BC provider registered in Android
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
        }
    }

    fun generateKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC", "BC")
        keyPairGenerator.initialize(params)
        val keyPair = keyPairGenerator.generateKeyPair()
        val privateKey = (keyPair.private as BCECPrivateKey).d.toByteArray().let {
            if (it.size == 33 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it
        }
        val publicKey = (keyPair.public as BCECPublicKey).q.getEncoded(true)

        return KeyPair(
            privateKey = privateKey,
            publicKey = publicKey,
        )
    }

    fun generateSharedSecret(
        privateKeyBytes: ByteArray,
        nodePubkey: String,
        derivationName: String? = null,
    ): ByteArray {
        val keyFactory = KeyFactory.getInstance("EC", "BC")
        val privateKey = keyFactory.generatePrivate(ECPrivateKeySpec(BigInteger(1, privateKeyBytes), params))

        val publicKeyPoint = params.curve.decodePoint(nodePubkey.fromHex())
        val publicKey = keyFactory.generatePublic(ECPublicKeySpec(publicKeyPoint, params))

        val baseSecret = KeyAgreement.getInstance("ECDH", "BC").run {
            // init(privateKey); doPhase(publicKey, true); generateSecret()
            val sharedPoint = (publicKey as ECPublicKey).q.multiply((privateKey as ECPrivateKey).d)
            sharedPoint.getEncoded(true)
        }

        if (derivationName != null) {
            val bytes = derivationName.toByteArray()
            val merged = baseSecret + bytes
            return sha256d(merged)
        }

        return baseSecret
    }

    fun encrypt(blob: ByteArray, secretKey: ByteArray): EncryptedPayload {
        require(secretKey.size == 32) { "Key must be 256 bits (32 bytes) for AES-256-GCM" }
        val key = SecretKeySpec(secretKey, "AES")

        val cipher = Cipher.getInstance(transformation).apply { init(Cipher.ENCRYPT_MODE, key) }
        val result = cipher.doFinal(blob)

        return EncryptedPayload(
            cipher = result.sliceArray(0 until result.size - 16),
            tag = result.sliceArray(result.size - 16 until result.size),
            iv = cipher.iv,
        )
    }

    fun decrypt(encryptedPayload: EncryptedPayload, secretKey: ByteArray): ByteArray {
        require(encryptedPayload.tag.size == 16) { "Tag must be 128 bits (8 bytes) for AES-GCM" }
        val key = SecretKeySpec(secretKey, "AES")

        val spec = GCMParameterSpec(128, encryptedPayload.iv)
        val cipher = Cipher.getInstance(transformation).apply { init(Cipher.DECRYPT_MODE, key, spec) }

        return cipher.doFinal(encryptedPayload.cipher + encryptedPayload.tag)
    }

    private fun sha256d(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").run { digest(digest(input)) }
    }
}
