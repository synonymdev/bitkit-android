package to.bitkit.ui.shared.toast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.models.Toast

private const val MAX_QUEUE_SIZE = 5

/**
 * Manages a queue of toasts to display sequentially.
 *
 * This ensures that toasts are shown one at a time without premature cancellation.
 * When a toast is displayed, it waits for its full visibility duration before
 * showing the next toast in the queue.
 *
 * Features:
 * - Thread-safe queue using StateFlow
 * - Sequential display (one toast at a time)
 * - Pause/resume timer on drag interactions
 * - Auto-advance to next toast on completion
 * - Max queue size with FIFO overflow handling
 */
class ToastQueueManager(private val scope: CoroutineScope) {
    // Public state exposed to UI
    private val _currentToast = MutableStateFlow<Toast?>(null)
    val currentToast: StateFlow<Toast?> = _currentToast.asStateFlow()

    // Internal queue state
    private val _queue = MutableStateFlow<List<Toast>>(emptyList())
    private var timerJob: Job? = null
    private var isPaused = false

    /**
     * Add toast to queue. If queue is full, drops oldest.
     */
    fun enqueue(toast: Toast) {
        _queue.update { current ->
            val newQueue = if (current.size >= MAX_QUEUE_SIZE) {
                // Drop oldest (first item) when queue full
                current.drop(1) + toast
            } else {
                current + toast
            }
            newQueue
        }
        // If no toast is currently displayed, show this one immediately
        showNextToastIfAvailable()
    }

    /**
     * Dismiss current toast and advance to next in queue.
     */
    fun dismissCurrentToast() {
        cancelTimer()
        _currentToast.value = null
        isPaused = false
        // Check if there are more toasts waiting and show next one
        showNextToastIfAvailable()
    }

    /**
     * Pause current toast timer (called on drag start).
     */
    fun pauseCurrentToast() {
        if (_currentToast.value?.autoHide == true) {
            isPaused = true
            cancelTimer()
        }
    }

    /**
     * Resume current toast timer with FULL duration (called on drag end).
     */
    fun resumeCurrentToast() {
        val toast = _currentToast.value
        if (isPaused && toast != null) {
            isPaused = false
            if (toast.autoHide) {
                startTimer(toast.visibilityTime)
            }
        }
    }

    /**
     * Clear all queued toasts and hide current toast.
     */
    fun clear() {
        cancelTimer()
        _queue.value = emptyList()
        _currentToast.value = null
        isPaused = false
    }

    private fun showNextToast() {
        val nextToast = _queue.value.firstOrNull() ?: return

        // Remove from queue
        _queue.update { it.drop(1) }

        // Display toast
        _currentToast.value = nextToast
        isPaused = false

        // Start auto-hide timer if enabled
        if (nextToast.autoHide) {
            startTimer(nextToast.visibilityTime)
        }
    }

    private fun startTimer(duration: Long) {
        cancelTimer()
        timerJob = scope.launch {
            delay(duration)
            if (!isPaused) {
                _currentToast.value = null
                // Show next toast if available
                showNextToastIfAvailable()
            }
        }
    }

    private fun showNextToastIfAvailable() {
        if (_currentToast.value == null && _queue.value.isNotEmpty()) {
            showNextToast()
        }
    }

    private fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
    }
}
