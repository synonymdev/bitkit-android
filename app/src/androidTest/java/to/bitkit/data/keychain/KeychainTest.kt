package to.bitkit.data.keychain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import to.bitkit.data.AppDb
import to.bitkit.data.entities.ConfigEntity
import to.bitkit.utils.KeychainError
import to.bitkit.test.BaseAndroidTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class KeychainTest : BaseAndroidTest() {

    private val appContext by lazy { ApplicationProvider.getApplicationContext<Context>() }
    private lateinit var db: AppDb

    private lateinit var sut: Keychain

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(appContext, AppDb::class.java).build().also {
            // seed db
            runBlocking {
                it.configDao().upsert(
                    ConfigEntity(
                        walletIndex = 0L,
                    ),
                )
            }
        }

        sut = Keychain(
            db,
            appContext,
            testDispatcher,
        )
    }

    @Test
    fun dbSeed() = test {
        val walletIndex = db.configDao().getAll().first().first().walletIndex

        assertEquals(0L, walletIndex)
    }

    @Test
    fun saveString_loadString() = test {
        val (key, value) = "key" to "value"

        sut.saveString(key, value)

        assertEquals(value, sut.loadString(key))
    }

    @Test
    fun saveString_existingKey_shouldThrow() = test {
        val key = "key"
        sut.saveString(key, "value1")

        assertFailsWith<KeychainError.FailedToSaveAlreadyExists> { sut.saveString(key, "value2") }
    }

    @Test
    fun delete() = test {
        val (key, value) = "keyToDelete" to "value"
        sut.saveString(key, value)

        sut.delete(key)

        assertNull(sut.loadString(key))
    }

    @Test
    fun exists() = test {
        val (key, value) = "keyToExist" to "value"
        sut.saveString(key, value)

        assertTrue { sut.exists(key) }
    }

    @Test
    fun wipe() = test {
        List(3) { sut.saveString("keyToWipe$it", "value$it") }

        sut.wipe()

        assertTrue { sut.snapshot.asMap().isEmpty() }
    }

    @Test
    fun pinAttemptsRemaining_shouldReturnDecryptedValue() = test {
        val attemptsRemaining = "3"
        sut.saveString(Keychain.Key.PIN_ATTEMPTS_REMAINING.name, attemptsRemaining)

        val result = sut.pinAttemptsRemaining().first()

        assertEquals(attemptsRemaining, result.toString())
    }

    @After
    fun tearDown() {
        db.close()
        sut.cancel()
    }
}
