package to.bitkit

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
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
        currentActivity = CurrentActivity().also { registerActivityLifecycleCallbacks(it) }
        Env.initAppStoragePath(filesDir.absolutePath)
    }

    companion object {
        @SuppressLint("StaticFieldLeak") // Should be safe given its manual memory management
        internal var currentActivity: CurrentActivity? = null
    }
}

// region currentActivity
class CurrentActivity : ActivityLifecycleCallbacks {
    var value: Activity? = null
        private set

    override fun onActivityCreated(activity: Activity, bundle: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) {
        this.value = activity
    }

    override fun onActivityResumed(activity: Activity) {
        this.value = activity
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        if (this.value == activity) this.value = null
    }

    override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (this.value == activity) this.value = null
    }
}
// endregion
