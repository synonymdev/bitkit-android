package to.bitkit

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import to.bitkit.appwidget.AppWidgetRefreshReason
import to.bitkit.appwidget.AppWidgetRefreshScheduler
import to.bitkit.env.Env
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.services.BluetoothInit
import to.bitkit.services.PubkyAuthHandlerRegistrar
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltAndroidApp
internal open class App : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var appWidgetRefreshScheduler: AppWidgetRefreshScheduler

    @Inject
    lateinit var pubkyAuthHandlerRegistrar: PubkyAuthHandlerRegistrar

    /** Resolved only once the process changes foreground state, so startup does not build the wallet graph. */
    @Inject
    lateinit var hwWalletRepo: Lazy<HwWalletRepo>

    override val workManagerConfiguration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        Env.initAppStoragePath(filesDir.absolutePath)
        installUncaughtExceptionLogger()
        SingletonImageLoader.setSafe { imageLoader }
        currentActivity = CurrentActivity().also { registerActivityLifecycleCallbacks(it) }
        appWidgetRefreshScheduler.ensureScheduled(AppWidgetRefreshReason.APP_START)
        // Initialize btleplug for Bluetooth support (required before any BLE usage)
        BluetoothInit.ensureInitialized()
        pubkyAuthHandlerRegistrar.start()
        observeAppForeground()
    }

    private fun observeAppForeground() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> hwWalletRepo.get().onAppForegrounded()
                    Lifecycle.Event.ON_STOP -> hwWalletRepo.get().onAppBackgrounded()
                    else -> Unit
                }
            },
        )
    }

    private fun installUncaughtExceptionLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Logger.error("Uncaught exception on thread '${thread.name}'", throwable, context = TAG)
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "App"

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
