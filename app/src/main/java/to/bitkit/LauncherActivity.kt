package to.bitkit

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import to.bitkit.bdk.BitcoinService
import to.bitkit.node.LightningService
import to.bitkit.ui.MainActivity
import to.bitkit.ui.initNotificationChannel
import to.bitkit.ui.logFcmToken
import kotlin.io.path.Path

class LauncherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initNotificationChannel()
        logFcmToken()
        warmupNode(filesDir.absolutePath)
        startActivity(Intent(this, MainActivity::class.java))
    }
}

internal fun warmupNode(basePath: String) {
    LightningService.shared.apply {
        init(basePath)
        start()
        sync()
    }
    BitcoinService.shared.apply {
        sync()
    }
}

internal fun ldkDir(base: String): String {
    require(base.isNotEmpty()) { "Base path for LDK storage cannot be empty" }
    return Path(base, "bitkit", "ldk-data")
        .toFile()
        // .also {
        //     if (!it.mkdirs()) throw Error("Cannot create LDK data directory")
        // }
        .absolutePath
}
