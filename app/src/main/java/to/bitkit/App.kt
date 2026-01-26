package to.bitkit

import android.annotation.SuppressLint
import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import to.bitkit.env.Env
import javax.inject.Inject

@HiltAndroidApp
internal open class App : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        lifecycle = AppLifecycle().also { registerActivityLifecycleCallbacks(it) }
        Env.initAppStoragePath(filesDir.absolutePath)
    }

    companion object {
        @SuppressLint("StaticFieldLeak") // Should be safe given the manual memory management
        internal var lifecycle: AppLifecycle? = null
    }
}
