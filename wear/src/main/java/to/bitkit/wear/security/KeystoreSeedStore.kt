package to.bitkit.wear.security

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import to.bitkit.wear.ext.runSuspendCatching
import to.bitkit.wear.utils.Logger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Stores the seed encrypted by [key] in one file under [directory], which must be app-private and outside every
 * backup. The file holds a format version and the [SeedKey.encrypt] output; nothing else on the watch persists the
 * seed. On a watch with a screen lock the key only works while the watch is unlocked; without one the key has no such
 * limit. Plaintext lives in byte arrays that are zeroed after use; the [WalletSeed] strings handed in and out are the
 * caller's to drop.
 */
class KeystoreSeedStore(
    private val directory: File,
    private val key: SeedKey,
    private val deviceLock: DeviceLock,
) : SeedStore {
    companion object {
        private const val TAG = "KeystoreSeedStore"
        private const val FILE_NAME = "seed.bin"
        private const val FORMAT_VERSION: Byte = 1
        private const val NO_PASSPHRASE = -1

        /** Binds a ciphertext to this use and format, so it cannot be mistaken for another blob of the same key. */
        private val aad = "to.bitkit.wear.seed.v1".toByteArray(Charsets.UTF_8)

        internal fun encode(seed: WalletSeed): ByteArray {
            val mnemonic = seed.mnemonic.toByteArray(Charsets.UTF_8)
            val passphrase = seed.passphrase?.toByteArray(Charsets.UTF_8)
            try {
                return ByteBuffer.allocate(Int.SIZE_BYTES * 2 + mnemonic.size + (passphrase?.size ?: 0))
                    .putInt(mnemonic.size)
                    .put(mnemonic)
                    .putInt(passphrase?.size ?: NO_PASSPHRASE)
                    .put(passphrase ?: ByteArray(0))
                    .array()
            } finally {
                mnemonic.fill(0)
                passphrase?.fill(0)
            }
        }

        internal fun decode(plaintext: ByteArray): WalletSeed = runCatching {
            val buffer = ByteBuffer.wrap(plaintext)
            val mnemonic = buffer.readString(buffer.getInt())
            val passphraseSize = buffer.getInt()
            val passphrase = if (passphraseSize == NO_PASSPHRASE) null else buffer.readString(passphraseSize)
            check(!buffer.hasRemaining()) { "Trailing bytes" }
            WalletSeed(mnemonic, passphrase)
        }.getOrElse { throw SeedStoreError.Corrupted() }

        private fun ByteBuffer.readString(size: Int): String {
            val bytes = ByteArray(size)
            try {
                get(bytes)
                return String(bytes, Charsets.UTF_8)
            } finally {
                bytes.fill(0)
            }
        }
    }

    private val mutex = Mutex()
    private val file get() = File(directory, FILE_NAME)

    override suspend fun exists(): Boolean = mutex.withLock { file.exists() }

    override suspend fun load(): Result<WalletSeed> = mutex.withLock {
        runSuspendCatching {
            if (!file.exists()) throw SeedStoreError.NotFound()
            if (!key.exists()) throw SeedStoreError.KeyInvalidated()
            val stored = file.readBytes()
            if (stored.isEmpty() || stored[0] != FORMAT_VERSION) throw SeedStoreError.Corrupted()
            val plaintext = key.decrypt(stored.copyOfRange(1, stored.size), aad)
            try {
                decode(plaintext)
            } finally {
                plaintext.fill(0)
            }
        }.recoverCatching {
            // A locked watch may refuse its key in ways that look like a lost key; never delete the seed for that.
            if (it is SeedStoreError.KeyInvalidated && isLocked()) throw SeedStoreError.DeviceLocked(it)
            throw it
        }.onFailure {
            if (it !is SeedStoreError.KeyInvalidated) return@onFailure
            Logger.warn("Deleting the seed because its key was invalidated", context = TAG)
            delete()
        }
    }

    override suspend fun save(seed: WalletSeed): Result<Unit> = mutex.withLock {
        runSuspendCatching {
            val plaintext = encode(seed)
            try {
                runCatching {
                    val isUnlockedDeviceRequired = deviceLock.isSecure
                    val backing = key.create(isUnlockedDeviceRequired)
                    Logger.info(
                        "Created seed key backed by '$backing' with unlocked device required " +
                            "'$isUnlockedDeviceRequired'",
                        context = TAG,
                    )
                    write(key.encrypt(plaintext, aad))
                }.onFailure { delete() }.getOrThrow()
            } finally {
                plaintext.fill(0)
            }
        }
    }

    override suspend fun wipe(): Result<Unit> = mutex.withLock { delete() }

    private fun isLocked(): Boolean {
        deviceLock.refresh()
        return deviceLock.isLocked.value
    }

    private fun delete(): Result<Unit> {
        val keyResult = runCatching { key.delete() }
        val fileResult = runCatching {
            val target = file
            if (!target.delete() && target.exists()) throw SeedStoreError.Failed()
        }
        return if (keyResult.isFailure) keyResult else fileResult
    }

    private fun write(blob: ByteArray) {
        directory.mkdirs()
        val bytes = ByteArrayOutputStream(blob.size + 1).apply {
            write(FORMAT_VERSION.toInt())
            write(blob)
        }.toByteArray()
        val temp = File(directory, "$FILE_NAME.tmp")
        FileOutputStream(temp).use {
            it.write(bytes)
            it.fd.sync()
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            throw SeedStoreError.Failed()
        }
    }
}
