package to.bitkit.ldk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import to.bitkit.ext.readAsset
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class LdkMigrationTest {
    private val mnemonic = "pool curve feature leader elite dilemma exile toast smile couch crane public"

    private val testContext by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val appContext by lazy { ApplicationProvider.getApplicationContext<Context>() }

    @Test
    fun nodeShouldStartFromBackupAfterMigration() {
        val seed = testContext.readAsset("ldk-backup/seed.bin")
        val manager = testContext.readAsset("ldk-backup/manager.bin")
        val monitor = testContext.readAsset("ldk-backup/monitor.bin")

        MigrationService(appContext).migrate(seed, manager, listOf(monitor))

        with(LightningService.shared) {
            init(mnemonic)
            start()

            assertTrue { nodeId == "02cd08b7b375e4263849121f9f0ffb2732a0b88d0fb74487575ac539b374f45a55" }
            assertTrue { channels.isNotEmpty() }

            stop()
        }
    }
}
