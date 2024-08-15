package to.bitkit.data.keychain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import to.bitkit.data.AppDb
import to.bitkit.data.entities.ConfigEntity
import to.bitkit.test.BaseTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class KeychainStoreTest : BaseTest() {

    private val appContext: Context by lazy { ApplicationProvider.getApplicationContext() }
    private lateinit var db: AppDb

    private lateinit var sut: KeychainStore

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

        sut = KeychainStore(
            db,
            appContext,
            testDispatcher,
        )
    }

    @Test
    fun dbSeed() = test {
        val config = db.configDao().getAll().first()

        assertTrue { config.first().walletIndex == 0L }
    }

    @Test
    fun saveString_loadString() = test {
        val (key, value) = "key" to "value"

        sut.saveString(key, value)

        assertEquals(value, sut.loadString(key))
    }

    @Test
    fun saveString_existingKey_shouldThrow() = test {
        assertFailsWith<IllegalArgumentException> {
            val key = "key"
            sut.saveString(key, "value1")
            sut.saveString(key, "value2")
        }
    }

    @Test
    fun delete() = test {
        val (key, value) = "keyToDelete" to "value"
        sut.saveString(key, value)

        sut.delete(key)

        assertNull(sut.loadString(key))
    }

    @Test
    fun exists() {
    }

    @Test
    fun wipe() = test {
        List(3) { sut.saveString("keyToWipe$it", "value$it") }

        sut.wipe()

        assertTrue { sut.snapshot.asMap().isEmpty() }
    }

    @After
    fun tearDown() {
        db.close()
        sut.cancel()
    }
}
