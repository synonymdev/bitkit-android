package to.bitkit.wear.security

import kotlinx.coroutines.flow.update
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import to.bitkit.wear.test.BaseUnitTest
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KeystoreSeedStoreTest : BaseUnitTest() {
    @get:Rule
    val folder = TemporaryFolder()

    private val key = FakeSeedKey()
    private val deviceLock = FakeDeviceLock()
    private val seed = WalletSeed(
        mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        passphrase = "TREZOR ü",
    )
    private val directory by lazy { File(folder.root, "seed") }
    private val sut by lazy { KeystoreSeedStore(directory, key, deviceLock) }

    @Test
    fun `a saved seed loads back with and without a passphrase`() = test {
        for (stored in listOf(seed, seed.copy(passphrase = null), seed.copy(passphrase = ""))) {
            sut.wipe().getOrThrow()
            assertTrue(sut.save(stored).isSuccess)

            assertTrue(sut.exists())
            assertEquals(stored, sut.load().getOrThrow())
        }
    }

    @Test
    fun `the seed file holds neither the words nor the passphrase`() = test {
        sut.save(seed).getOrThrow()

        val files = directory.listFiles().orEmpty()
        assertEquals(listOf("seed.bin"), files.map { it.name })
        val stored = files.single().readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("abandon" in stored)
        assertFalse("TREZOR" in stored)
    }

    @Test
    fun `save zeroes the plaintext it handed to the key`() = test {
        sut.save(seed).getOrThrow()

        assertTrue(key.plaintexts.single().all { it == 0.toByte() })
    }

    @Test
    fun `the key requires an unlocked watch only when the watch has a screen lock`() = test {
        sut.save(seed).getOrThrow()
        assertEquals(true, key.isUnlockedDeviceRequired)

        deviceLock.isSecure = false
        sut.wipe().getOrThrow()
        sut.save(seed).getOrThrow()
        assertEquals(false, key.isUnlockedDeviceRequired)
        assertEquals(seed, sut.load().getOrThrow())
    }

    @Test
    fun `a failed save leaves neither a key nor a file`() = test {
        key.failure = SeedStoreError.DeviceLocked()

        assertIs<SeedStoreError.DeviceLocked>(sut.save(seed).exceptionOrNull())
        assertFalse(key.exists())
        assertFalse(sut.exists())
    }

    @Test
    fun `each save after a wipe uses a new key`() = test {
        sut.save(seed).getOrThrow()
        sut.wipe().getOrThrow()
        sut.save(seed).getOrThrow()

        assertEquals(2, key.createCount)
    }

    @Test
    fun `save refuses to replace a stored seed and leaves it readable`() = test {
        sut.save(seed).getOrThrow()

        val result = sut.save(seed.copy(mnemonic = "zoo zoo zoo"))

        assertIs<SeedStoreError.AlreadyExists>(result.exceptionOrNull())
        assertEquals(1, key.createCount)
        assertEquals(seed, sut.load().getOrThrow())
    }

    @Test
    fun `load without a seed reports not found`() = test {
        assertIs<SeedStoreError.NotFound>(sut.load().exceptionOrNull())
    }

    @Test
    fun `load reports a missing key and deletes nothing`() = test {
        sut.save(seed).getOrThrow()
        key.invalidate()

        assertIs<SeedStoreError.KeyInvalidated>(sut.load().exceptionOrNull())
        assertTrue(sut.exists())
        assertIs<SeedStoreError.KeyInvalidated>(sut.load().exceptionOrNull())
    }

    @Test
    fun `load reports a lost key only after reading it twice`() = test {
        sut.save(seed).getOrThrow()
        key.failure = SeedStoreError.KeyInvalidated()

        assertIs<SeedStoreError.KeyInvalidated>(sut.load().exceptionOrNull())
        assertEquals(2, key.decryptCount)
        assertTrue(sut.exists())
        assertTrue(key.exists())
    }

    @Test
    fun `load returns the seed when the key only looked lost once`() = test {
        sut.save(seed).getOrThrow()
        key.lostKeyReads = 1

        assertEquals(seed, sut.load().getOrThrow())
        assertEquals(2, key.decryptCount)
    }

    @Test
    fun `load does not read the key again after any other failure`() = test {
        sut.save(seed).getOrThrow()
        key.failure = SeedStoreError.Failed()

        assertIs<SeedStoreError.Failed>(sut.load().exceptionOrNull())
        assertEquals(1, key.decryptCount)
        assertTrue(sut.exists())
    }

    @Test
    fun `load never deletes the seed for a key that looks lost while the watch is locked`() = test {
        sut.save(seed).getOrThrow()
        key.failure = SeedStoreError.KeyInvalidated()
        deviceLock.isLocked.update { true }

        assertIs<SeedStoreError.DeviceLocked>(sut.load().exceptionOrNull())
        assertTrue(sut.exists())

        key.failure = null
        deviceLock.isLocked.update { false }
        assertEquals(seed, sut.load().getOrThrow())
    }

    @Test
    fun `load keeps the seed while the watch is locked`() = test {
        sut.save(seed).getOrThrow()
        key.failure = SeedStoreError.DeviceLocked()

        assertIs<SeedStoreError.DeviceLocked>(sut.load().exceptionOrNull())

        key.failure = null
        assertEquals(seed, sut.load().getOrThrow())
    }

    @Test
    fun `load rejects a tampered or unknown file and keeps it`() = test {
        sut.save(seed).getOrThrow()
        val file = File(directory, "seed.bin")
        val stored = file.readBytes()

        file.writeBytes(byteArrayOf(9) + stored.drop(1))
        assertIs<SeedStoreError.Corrupted>(sut.load().exceptionOrNull())

        file.writeBytes(stored.copyOf().also { it[1] = (it[1] + 1).toByte() })
        assertIs<SeedStoreError.Corrupted>(sut.load().exceptionOrNull())
        assertTrue(sut.exists())
    }

    @Test
    fun `wipe deletes the key and the file`() = test {
        sut.save(seed).getOrThrow()

        assertTrue(sut.wipe().isSuccess)

        assertFalse(key.exists())
        assertFalse(sut.exists())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertTrue(sut.wipe().isSuccess)
    }

    @Test
    fun `wipe also deletes a seed file left half written`() = test {
        sut.save(seed).getOrThrow()
        File(directory, "seed.bin.tmp").writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(sut.wipe().isSuccess)

        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `decode rejects malformed plaintext`() {
        val encoded = KeystoreSeedStore.encode(seed)

        assertEquals(seed, KeystoreSeedStore.decode(encoded))
        for (malformed in listOf(ByteArray(0), encoded.copyOf(encoded.size - 1), encoded + 0, ByteArray(8) { -1 })) {
            runCatching { KeystoreSeedStore.decode(malformed) }
                .let { assertIs<SeedStoreError.Corrupted>(it.exceptionOrNull()) }
        }
    }
}
