package to.bitkit.utils.timedsheets

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import to.bitkit.ui.components.TimedSheetType
import to.bitkit.utils.Logger

class TimedSheetManager(private val scope: CoroutineScope) {
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

    fun onHomeScreenEntered() {
        Logger.debug("User entered home screen, starting timer", context = TAG)
        checkJob?.cancel()
        checkJob = scope.launch {
            delay(CHECK_DELAY_MILLIS)
            checkAndShowNextSheet()
        }
    }

    fun onHomeScreenExited() {
        Logger.debug("User exited home screen, cancelling timer", context = TAG)
        checkJob?.cancel()
        checkJob = null
    }

    fun dismissCurrentSheet() {
        if (currentTimedSheet == null) return

        scope.launch {
            currentTimedSheet?.onDismissed()
            _currentSheet.value = null
            currentTimedSheet = null

            Logger.debug("Clearing timed sheet queue", context = TAG)
        }
    }

    private suspend fun checkAndShowNextSheet() {
        Logger.debug("Registered sheets: ${registeredSheets.map { it.type.name }}")
        for (sheet in registeredSheets.toList()) {
            if (sheet.shouldShow()) {
                Logger.debug(
                    "Showing timed sheet: ${sheet.type.name} with priority: ${sheet.priority}",
                    context = TAG
                )
                currentTimedSheet = sheet
                _currentSheet.value = sheet.type
                sheet.onShown()
                registeredSheets.remove(sheet)
                return
            }
        }

        Logger.debug("No timed sheets need to be shown", context = TAG)
        _currentSheet.value = null
        currentTimedSheet = null
    }

    companion object {
        private const val TAG = "TimedSheetManager"
        private const val CHECK_DELAY_MILLIS = 2000L
    }
}
