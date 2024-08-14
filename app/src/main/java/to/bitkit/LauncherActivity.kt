package to.bitkit

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import to.bitkit.ldk.warmupNode
import to.bitkit.ui.MainActivity
import to.bitkit.ui.initNotificationChannel
import to.bitkit.ui.logFcmToken

@AndroidEntryPoint
class LauncherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initNotificationChannel()
        logFcmToken()
        warmupNode()
        startActivity(Intent(this, MainActivity::class.java))
    }
}
