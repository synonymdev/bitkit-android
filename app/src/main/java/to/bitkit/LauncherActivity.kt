package to.bitkit

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import to.bitkit.ldk.warmupNode
import to.bitkit.ui.MainActivity
import to.bitkit.ui.initNotificationChannel
import to.bitkit.ui.logFcmToken

class LauncherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initNotificationChannel()
        logFcmToken()
        warmupNode(filesDir.absolutePath)
        startActivity(Intent(this, MainActivity::class.java))
    }
}
