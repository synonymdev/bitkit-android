package to.bitkit.models

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

    val displayState: String
        get() = when (this) {
            is Stopped -> "Stopped"
            is Starting -> "Starting"
            is Running -> "Running"
            is Stopping -> "Stopping"
            is ErrorStarting -> "Error starting: ${cause.message}"
            is Initializing -> "Setting up wallet..."
        }
}
