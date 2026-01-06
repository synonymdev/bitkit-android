package to.bitkit.models

import android.content.Context
import to.bitkit.R

sealed class NodeLifecycleState {
    data object Stopped : NodeLifecycleState()
    data object Starting : NodeLifecycleState()
    data object Running : NodeLifecycleState()
    data object Stopping : NodeLifecycleState()
    data class ErrorStarting(val cause: Throwable) : NodeLifecycleState()
    data object Initializing : NodeLifecycleState()

    fun isStoppedOrStopping() = this is Stopped || this is Stopping
    fun isRunningOrStarting() = this is Running || this is Starting
    fun isStarting() = this is Starting
    fun isRunning() = this is Running
    fun canRun() = this.isRunningOrStarting() || this is Initializing

    fun uiText(context: Context): String = when (this) {
        is Stopped -> context.getString(R.string.other__node_stopped)
        is Starting -> context.getString(R.string.other__node_starting)
        is Running -> context.getString(R.string.other__node_running)
        is Stopping -> context.getString(R.string.other__node_stopping)
        is ErrorStarting -> context.getString(R.string.other__node_error_starting, cause.message ?: "")
        is Initializing -> context.getString(R.string.other__node_initializing)
    }

    fun asHealth() = when (this) {
        Running -> HealthState.READY
        Starting, Initializing, Stopping -> HealthState.PENDING
        Stopped, is ErrorStarting -> HealthState.ERROR
    }
}
