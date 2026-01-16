package to.bitkit.utils

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ParametersWithRandom
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.interfaces.ECPrivateKey
import org.bouncycastle.jce.interfaces.ECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPrivateKeySpec
import org.bouncycastle.jce.spec.ECPublicKeySpec
import org.bouncycastle.util.BigIntegers
import to.bitkit.ext.fromHex
import to.bitkit.ext.toHex
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("SwallowedException", "MagicNumber", "TooGenericExceptionCaught")
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
        // TODO move init to VM (to enable error handling on UI)?
        try {
            val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            when {
                provider == null -> Security.addProvider(BouncyCastleProvider())
                provider::class.java != BouncyCastleProvider::class.java -> {
                    // We substitute the outdated BC provider registered in Android
                    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                    Security.insertProviderAt(BouncyCastleProvider(), 1)
                }
            }
        } catch (e: Exception) {
            throw CryptoError.SecurityProviderSetupFailed()
        }
    }

    fun generateKeyPair(): KeyPair {
        try {
            val (privateKey, publicKey) = KeyPairGenerator.getInstance("EC", "BC").run {
                initialize(params)
                val keys = generateKeyPair()
                val private = (keys.private as BCECPrivateKey).run { BigIntegers.asUnsignedByteArray(32, d) }
                val public = (keys.public as BCECPublicKey).run { q.getEncoded(true) }
                private to public
            }

            return KeyPair(
                privateKey = privateKey,
                publicKey = publicKey,
            )
        } catch (e: Exception) {
            throw CryptoError.KeypairGenerationFailed()
        }
    }

    fun generateSharedSecret(
        privateKeyBytes: ByteArray,
        nodePubkey: String,
        derivationName: String? = null,
    ): ByteArray {
        try {
            val keyFactory = KeyFactory.getInstance("EC", "BC")
            val privateKey = keyFactory.generatePrivate(ECPrivateKeySpec(BigInteger(1, privateKeyBytes), params))
            val publicKey = let {
                val publicKeyPoint = params.curve.decodePoint(nodePubkey.fromHex())
                keyFactory.generatePublic(ECPublicKeySpec(publicKeyPoint, params))
            }

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
        } catch (e: Exception) {
            throw CryptoError.SharedSecretGenerationFailed()
        }
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
        try {
            require(encryptedPayload.tag.size == 16) { "Tag must be 128 bits (8 bytes) for AES-GCM" }
            val key = SecretKeySpec(secretKey, "AES")

            val spec = GCMParameterSpec(128, encryptedPayload.iv)
            val cipher = Cipher.getInstance(transformation).apply { init(Cipher.DECRYPT_MODE, key, spec) }

            return cipher.doFinal(encryptedPayload.cipher + encryptedPayload.tag)
        } catch (e: Exception) {
            throw CryptoError.DecryptionFailed()
        }
    }

    fun sign(message: String, privateKey: ByteArray): String = runCatching {
        val lightningPrefix = "Lightning Signed Message:"
        val prefixedMessage = lightningPrefix + message
        val hash1 = MessageDigest.getInstance("SHA-256").digest(prefixedMessage.toByteArray())
        val messageHash = MessageDigest.getInstance("SHA-256").digest(hash1)

        val curve: X9ECParameters = SECNamedCurves.getByName("secp256k1")
        val privateKeyBigInt = BigInteger(1, privateKey)
        val domainParams = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
        val privateKeyParams = ECPrivateKeyParameters(privateKeyBigInt, domainParams)

        val signer = ECDSASigner()
        signer.init(true, ParametersWithRandom(privateKeyParams, SecureRandom()))

        val components = signer.generateSignature(messageHash)
        val r = components[0]
        val s = components[1]

        val n = curve.n
        val halfOrder = n.shiftRight(1)
        if (s > halfOrder) {
            val s2 = n.subtract(s)
            val recId = calculateRecoveryId(r, s2, messageHash, privateKeyBigInt, curve)
            formatSignature(recId, r, s2)
        } else {
            val recId = calculateRecoveryId(r, s, messageHash, privateKeyBigInt, curve)
            formatSignature(recId, r, s)
        }
    }.getOrElse { throw CryptoError.SigningFailed() }

    fun getPublicKey(privateKey: ByteArray): ByteArray = runCatching {
        val keyFactory = KeyFactory.getInstance("EC", "BC")
        val privateKeySpec = ECPrivateKeySpec(BigInteger(1, privateKey), params)
        val privateKeyObj = keyFactory.generatePrivate(privateKeySpec)

        val publicKeyPoint = params.g.multiply((privateKeyObj as ECPrivateKey).d)
        publicKeyPoint.getEncoded(true)
    }.getOrElse { throw CryptoError.PublicKeyCreationFailed() }

    private fun calculateRecoveryId(
        r: BigInteger,
        s: BigInteger,
        messageHash: ByteArray,
        privateKey: BigInteger,
        curve: X9ECParameters,
    ): Int {
        val expectedPublicKey = curve.g.multiply(privateKey)
        val n = curve.n
        val e = BigInteger(1, messageHash)
        val rInv = r.modInverse(n)

        for (recId in 0..3) {
            try {
                val x = if (recId >= 2) r.add(n) else r
                val xBytes = BigIntegers.asUnsignedByteArray(32, x)

                val yEven = curve.curve.decodePoint(byteArrayOf(0x02) + xBytes)
                val yOdd = curve.curve.decodePoint(byteArrayOf(0x03) + xBytes)

                val recoveryPoint = if (recId % 2 == 0) yEven else yOdd

                val candidatePublicKey = recoveryPoint.multiply(s.multiply(rInv).mod(n))
                    .subtract(curve.g.multiply(e.multiply(rInv).mod(n)))

                if (candidatePublicKey.equals(expectedPublicKey)) {
                    return recId
                }
            } catch (_: Exception) {
                continue
            }
        }
        throw CryptoError.SigningFailed()
    }

    private fun formatSignature(recId: Int, r: BigInteger, s: BigInteger): String {
        val recIdByte = (recId + 31).toByte()
        val rBytes = BigIntegers.asUnsignedByteArray(32, r)
        val sBytes = BigIntegers.asUnsignedByteArray(32, s)
        val signature = byteArrayOf(recIdByte) + rBytes + sBytes
        return signature.toHex()
    }

    private fun sha256d(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").run { digest(digest(input)) }
    }
}

sealed class CryptoError(message: String) : AppError(message) {
    class SharedSecretGenerationFailed : CryptoError("Shared secret generation failed")
    class SecurityProviderSetupFailed : CryptoError("Security provider setup failed")
    class KeypairGenerationFailed : CryptoError("Keypair generation failed")
    class DecryptionFailed : CryptoError("Decryption failed")
    class SigningFailed : CryptoError("Signing failed")
    class PublicKeyCreationFailed : CryptoError("Public key creation failed")
}
