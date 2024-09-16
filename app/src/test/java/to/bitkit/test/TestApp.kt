package to.bitkit.test

import android.app.Application
import com.google.firebase.FirebaseApp

class TestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // FirebaseApp.initializeApp(this)
    }
}
