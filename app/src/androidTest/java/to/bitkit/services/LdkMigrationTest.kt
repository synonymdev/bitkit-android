package to.bitkit.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.ext.readAsset
import javax.inject.Inject
import kotlin.test.assertTrue

@HiltAndroidTest
class LdkMigrationTest {
    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var keychain: Keychain

    @Inject
    lateinit var lightningService: LightningService

    private val mnemonic = "pool curve feature leader elite dilemma exile toast smile couch crane public"

    private val testContext by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val appContext = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun init() {
        hiltRule.inject()
        Env.initAppStoragePath(appContext.filesDir.absolutePath)
        runBlocking { keychain.saveString(Keychain.Key.BIP39_MNEMONIC.name, mnemonic) }
    }

    @Test
    fun nodeShouldStartFromBackupAfterMigration() = runBlocking {
//        TODO Fix or remove check on channel size
//        val seed = testContext.readAsset("ldk-backup/seed.bin")
//        val manager = testContext.readAsset("ldk-backup/manager.bin")
//        val monitor = testContext.readAsset("ldk-backup/monitor.bin")
//
//        MigrationService(appContext).migrate(seed, manager, listOf(monitor))
//
//        with(lightningService) {
//            setup(walletIndex = 0)
//            runBlocking { start() }
//
//            assertTrue { nodeId == "02cd08b7b375e4263849121f9f0ffb2732a0b88d0fb74487575ac539b374f45a55" }
//            assertTrue { channels?.isNotEmpty() == true }
//
//            runBlocking { stop() }
//        }
    }
}
