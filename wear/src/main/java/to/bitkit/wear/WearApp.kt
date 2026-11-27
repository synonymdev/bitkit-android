package to.bitkit.wear

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import to.bitkit.wear.env.Env
import to.bitkit.wear.utils.Logger

@HiltAndroidApp
class WearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Env.isDebug) Logger.init(filesDir)
    }
}
