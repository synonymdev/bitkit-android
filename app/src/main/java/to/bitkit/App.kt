package to.bitkit

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import to.bitkit.appwidget.AppWidgetRefreshReceiver
import to.bitkit.env.Env
import to.bitkit.services.BluetoothInit
import javax.inject.Inject

@HiltAndroidApp
internal open class App : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var imageLoader: ImageLoader

    override val workManagerConfiguration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        SingletonImageLoader.setSafe { imageLoader }
        currentActivity = CurrentActivity().also { registerActivityLifecycleCallbacks(it) }
        registerAppWidgetRefreshReceiver()
        Env.initAppStoragePath(filesDir.absolutePath)
        // Initialize btleplug for Bluetooth support (required before any BLE usage)
        BluetoothInit.ensureInitialized()
    }

    private fun registerAppWidgetRefreshReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            AppWidgetRefreshReceiver(),
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    companion object {
        @SuppressLint("StaticFieldLeak") // Should be safe given its manual memory management
        internal var currentActivity: CurrentActivity? = null
    }
}

class CurrentActivity : ActivityLifecycleCallbacks {
    var value: Activity? = null
        private set

    override fun onActivityCreated(activity: Activity, bundle: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = run { this.value = activity }
    override fun onActivityResumed(activity: Activity) = run { this.value = activity }
    override fun onActivityPaused(activity: Activity) = clearIfCurrent(activity)
    override fun onActivityStopped(activity: Activity) = clearIfCurrent(activity)
    override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = clearIfCurrent(activity)

    private fun clearIfCurrent(activity: Activity) = run { if (this.value == activity) this.value = null }
}
