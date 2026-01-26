package to.bitkit

import android.app.Activity
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle

fun interface AppLifecycleListener {
    fun onAppLifecycleChanged(isInForeground: Boolean)
}

class AppLifecycle : ActivityLifecycleCallbacks {
    var activity: Activity? = null
        private set

    private val listeners = mutableListOf<AppLifecycleListener>()

    val isInForeground: Boolean get() = activity != null

    fun addListener(listener: AppLifecycleListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: AppLifecycleListener) {
        listeners.remove(listener)
    }

    override fun onActivityCreated(activity: Activity, bundle: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) {
        val wasInBackground = this.activity == null
        this.activity = activity
        if (wasInBackground) notifyListeners(isInForeground = true)
    }

    override fun onActivityResumed(activity: Activity) = run { this.activity = activity }

    override fun onActivityPaused(activity: Activity) = clearIfCurrent(activity)

    override fun onActivityStopped(activity: Activity) {
        val wasInForeground = this.activity != null
        clearIfCurrent(activity)
        if (wasInForeground && this.activity == null) notifyListeners(isInForeground = false)
    }

    override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = clearIfCurrent(activity)

    private fun clearIfCurrent(activity: Activity) = run { if (this.activity == activity) this.activity = null }

    private fun notifyListeners(isInForeground: Boolean) {
        listeners.forEach { it.onAppLifecycleChanged(isInForeground) }
    }
}
