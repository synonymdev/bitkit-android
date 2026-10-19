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

class TimedSheetManager(
    private val scope: CoroutineScope,
    private val tryShowSheet: (TimedSheetType) -> Boolean,
) {
    private val _currentSheet = MutableStateFlow<TimedSheetType?>(null)
    val currentSheet: StateFlow<TimedSheetType?> = _currentSheet.asStateFlow()

    private val registeredSheets = mutableListOf<TimedSheetItem>()
    private var currentTimedSheet: TimedSheetItem? = null
    private var checkJob: Job? = null
    private var isOnHomeScreen = false

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
        isOnHomeScreen = true
        reevaluate()
    }

    fun reevaluate() {
        if (!isOnHomeScreen || currentTimedSheet != null) return
        checkJob?.cancel()
        checkJob = scope.launch {
            delay(CHECK_DELAY_MILLIS)
            checkAndShowNextSheet()
        }
    }

    fun onHomeScreenExited() {
        isOnHomeScreen = false
        Logger.debug("User exited home screen, cancelling timer", context = TAG)
        checkJob?.cancel()
        checkJob = null
    }

    fun dismissCurrentSheet() {
        val sheet = currentTimedSheet ?: return

        scope.launch {
            sheet.onDismissed()
            currentTimedSheet = null
            _currentSheet.update { null }

            Logger.debug("Clearing timed sheet queue", context = TAG)
        }
    }

    private suspend fun checkAndShowNextSheet() {
        if (!isOnHomeScreen || currentTimedSheet != null) return
        for (sheet in registeredSheets.toList()) {
            val eligible = sheet.shouldShow()
            currentCoroutineContext().ensureActive()
            if (!isOnHomeScreen || currentTimedSheet != null) return
            if (eligible) {
                if (!tryShowSheet(sheet.type)) return
                Logger.debug(
                    "Showing timed sheet: ${sheet.type.name} with priority: ${sheet.priority}",
                    context = TAG
                )
                currentTimedSheet = sheet
                _currentSheet.update { sheet.type }
                sheet.onShown()
                registeredSheets.remove(sheet)
                return
            }
        }

        Logger.debug("No timed sheets need to be shown", context = TAG)
    }

    companion object {
        private const val TAG = "TimedSheetManager"
        private const val CHECK_DELAY_MILLIS = 2000L
    }
}
