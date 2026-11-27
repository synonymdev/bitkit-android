package to.bitkit.wear.security

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import to.bitkit.wear.ext.runSuspendCatching
import to.bitkit.wear.utils.Logger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.time.Duration.Companion.milliseconds

/**
 * Stores the seed encrypted by [key] in one file under the directory, which must be app-private and outside every
 * backup; it is resolved on first use, off the main thread. The file holds a format version and the
 * [SeedKey.encrypt] output; nothing else on the watch persists the seed. Unless [isSoftwareKeyAllowed], which only
 * debug builds set, for the emulators, a seed is never saved under a key outside secure hardware: the save fails and
 * the key is deleted. On a watch with a screen lock the key only works while the watch is unlocked; without one the
 * key has no such limit. A key that looks lost is read a second time, a moment later, before [load] reports it, and
 * [load] never deletes the seed. Plaintext lives in byte arrays that are zeroed after use; the [WalletSeed] strings
 * handed in and out are the caller's to drop.
 */
class KeystoreSeedStore(
    directory: Lazy<File>,
    private val key: SeedKey,
    private val deviceLock: DeviceLock,
    private val isSoftwareKeyAllowed: Boolean,
) : SeedStore {
    companion object {
        private const val TAG = "KeystoreSeedStore"
        private const val FILE_NAME = "seed.bin"
        private const val FORMAT_VERSION: Byte = 1
        private const val NO_PASSPHRASE = -1

        /** How long a key that looks lost is left alone before it is read again, so a passing Keystore hiccup ends. */
        val KEY_LOSS_RECHECK_DELAY = 500.milliseconds

        /** Where a key is out of reach of the system's own software. */
        private val secureBackings = setOf(KeyBacking.StrongBox, KeyBacking.TrustedEnvironment)

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
    private val directory by directory
    private val file get() = File(directory, FILE_NAME)
    private val tempFile get() = File(directory, "$FILE_NAME.tmp")

    override suspend fun exists(): Boolean = mutex.withLock { file.exists() }

    override suspend fun load(): Result<WalletSeed> = mutex.withLock {
        runSuspendCatching {
            if (!file.exists()) throw SeedStoreError.NotFound()
            val stored = file.readBytes()
            if (stored.isEmpty() || stored[0] != FORMAT_VERSION) throw SeedStoreError.Corrupted()
            val blob = stored.copyOfRange(1, stored.size)
            val plaintext = decrypt(blob)
            try {
                decode(plaintext)
            } finally {
                plaintext.fill(0)
            }
        }
    }

    override suspend fun save(seed: WalletSeed): Result<Unit> = mutex.withLock {
        runSuspendCatching {
            if (file.exists()) throw SeedStoreError.AlreadyExists()
            val plaintext = encode(seed)
            try {
                runCatching {
                    val isUnlockedDeviceRequired = deviceLock.isSecure
                    val backing = key.create(isUnlockedDeviceRequired)
                    if (!isSoftwareKeyAllowed && backing !in secureBackings) throw SeedStoreError.InsecureKey(backing)
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

    private suspend fun decrypt(blob: ByteArray): ByteArray {
        val first = runCatching { key.decrypt(blob, aad) }
        if (first.exceptionOrNull() !is SeedStoreError.KeyInvalidated) return first.getOrThrow()
        delay(KEY_LOSS_RECHECK_DELAY)
        val second = runCatching { key.decrypt(blob, aad) }
        val error = second.exceptionOrNull()
        if (error is SeedStoreError.KeyInvalidated && isLocked()) throw SeedStoreError.DeviceLocked(error)
        return second.getOrThrow()
    }

    private fun isLocked(): Boolean {
        deviceLock.refresh()
        return deviceLock.isLocked.value
    }

    private fun delete(): Result<Unit> {
        runCatching { key.delete() }
            .recoverCatching { key.delete() }
            .onFailure { Logger.warn("Failed to delete the seed key", it, context = TAG) }
        return runCatching {
            for (target in listOf(file, tempFile)) {
                if (!target.delete() && target.exists()) throw SeedStoreError.Failed()
            }
            if (directory.exists()) syncDirectory()
        }
    }

    private fun write(blob: ByteArray) {
        directory.mkdirs()
        val bytes = ByteArrayOutputStream(blob.size + 1).apply {
            write(FORMAT_VERSION.toInt())
            write(blob)
        }.toByteArray()
        val temp = tempFile
        FileOutputStream(temp).use {
            it.write(bytes)
            it.fd.sync()
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            throw SeedStoreError.Failed()
        }
        syncDirectory()
    }

    private fun syncDirectory() {
        runCatching { FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } }
            .onFailure { Logger.warn("Failed to sync the seed directory", it, context = TAG) }
    }
}
