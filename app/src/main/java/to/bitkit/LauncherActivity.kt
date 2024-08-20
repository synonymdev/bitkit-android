package to.bitkit

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import to.bitkit.ui.MainActivity
import to.bitkit.ui.SharedViewModel
import to.bitkit.ui.initNotificationChannel
import to.bitkit.ui.logFcmToken

@AndroidEntryPoint
class LauncherActivity : AppCompatActivity() {
    private val sharedViewModel by viewModels<SharedViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initNotificationChannel()
        logFcmToken()
        sharedViewModel.warmupNode()
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
    }
}
