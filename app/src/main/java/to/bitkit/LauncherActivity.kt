package to.bitkit

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import to.bitkit.ext.toByteArray
import to.bitkit.ldk.MigrationService
import to.bitkit.ui.initNotificationChannel
import to.bitkit.ui.logFcmToken
import java.io.InputStream
import javax.inject.Inject

@AndroidEntryPoint
class LauncherActivity : AppCompatActivity() {

    @Inject lateinit var migrationService: MigrationService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initNotificationChannel()
        logFcmToken()
        migrateLdkBackup()
        // warmupNode()
        // startActivity(Intent(this, MainActivity::class.java))
    }

    private fun migrateLdkBackup() {
        val seed = SEED.toByteArray()
        val manager = assets.open("test/channel_manager.bin").use(InputStream::readBytes)
        // TODO provide test monitors
        val monitors = emptyList<ByteArray>()

        migrationService.migrate(seed, manager, monitors)
    }
}
