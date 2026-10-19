package to.bitkit.utils.timedsheets

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.ui.components.TimedSheetType
import to.bitkit.utils.Logger
import kotlin.time.Duration.Companion.seconds

class TimedSheetManager(private val scope: CoroutineScope) {
    companion object {
        private const val TAG = "TimedSheetManager"

        /** Delay before optional reminders may appear on Home. */
        private val CHECK_DELAY = 2.seconds
    }

    private val _currentSheet = MutableStateFlow<TimedSheetType?>(null)
    val currentSheet: StateFlow<TimedSheetType?> = _currentSheet.asStateFlow()

    private val registeredSheets = mutableListOf<TimedSheetItem>()
    private var currentTimedSheet: TimedSheetItem? = null
    private var checkJob: Job? = null

    fun registerSheet(sheet: TimedSheetItem) {
        registeredSheets.add(sheet)
        registeredSheets.sortByDescending { it.priority }
        Logger.debug(
            "Registered timed sheet: ${sheet.type.name} with priority: ${sheet.priority}",
            context = TAG
        )
    }

    fun onHomeScreenEntered(
        canShowSheet: () -> Boolean,
        showSheet: (TimedSheetType) -> Unit,
    ) {
        cancelPendingCheck()
        if (!canShowSheet()) return

        Logger.debug("User entered home screen, starting timer", context = TAG)
        checkJob = scope.launch {
            delay(CHECK_DELAY)
            checkAndShowNextSheet(canShowSheet, showSheet)
        }
    }

    fun onHomeScreenExited() {
        Logger.debug("User exited home screen, cancelling timer", context = TAG)
        cancelPendingCheck()
    }

    fun cancelPendingCheck() {
        checkJob?.cancel()
        checkJob = null
    }

    fun dismissCurrentSheet() {
        if (currentTimedSheet == null) return

        scope.launch {
            currentTimedSheet?.onDismissed()
            _currentSheet.update { null }
            currentTimedSheet = null

            Logger.debug("Clearing timed sheet queue", context = TAG)
        }
    }

    private suspend fun checkAndShowNextSheet(
        canShowSheet: () -> Boolean,
        showSheet: (TimedSheetType) -> Unit,
    ) {
        if (!canShowSheet()) return

        Logger.debug("Registered sheets: ${registeredSheets.map { it.type.name }}", context = TAG)
        for (sheet in registeredSheets.toList()) {
            val shouldShow = sheet.shouldShow()
            currentCoroutineContext().ensureActive()
            if (!canShowSheet()) return

            if (shouldShow) {
                Logger.debug(
                    "Showing timed sheet: ${sheet.type.name} with priority: ${sheet.priority}",
                    context = TAG
                )
                // Presentation cancels pending checks; this check has already committed its sheet.
                checkJob = null
                currentTimedSheet = sheet
                _currentSheet.update { sheet.type }
                registeredSheets.remove(sheet)
                showSheet(sheet.type)
                sheet.onShown()
                return
            }
        }

        Logger.debug("No timed sheets need to be shown", context = TAG)
        _currentSheet.update { null }
        currentTimedSheet = null
    }
}
