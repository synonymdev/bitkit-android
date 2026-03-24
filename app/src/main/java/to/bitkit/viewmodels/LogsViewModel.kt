package to.bitkit.viewmodels

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.env.Env
import to.bitkit.repositories.LogFile
import to.bitkit.repositories.LogsRepo
import to.bitkit.utils.Logger
import java.io.File
import javax.inject.Inject

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val application: Application,
    private val logsRepo: LogsRepo,
) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "LogsViewModel"
    }

    private val _logs = MutableStateFlow<ImmutableList<LogFile>>(persistentListOf())
    val logs: StateFlow<ImmutableList<LogFile>> = _logs.asStateFlow()

    private val _selectedLogContent = MutableStateFlow<ImmutableList<String>>(persistentListOf())
    val selectedLogContent: StateFlow<ImmutableList<String>> = _selectedLogContent.asStateFlow()

    fun loadLogs() {
        viewModelScope.launch {
            val logFiles = logsRepo.getLogs().getOrDefault(emptyList()).toImmutableList()
            _logs.update { logFiles }
        }
    }

    fun loadLogContent(logFile: LogFile) {
        viewModelScope.launch {
            logsRepo.loadLogContent(logFile)
                .onSuccess { content ->
                    _selectedLogContent.update { content.toImmutableList() }
                }
                .onFailure { e ->
                    _selectedLogContent.update { persistentListOf("Log file not found") }
                    Logger.error("Failed to load log content", e, context = TAG)
                }
        }
    }

    fun prepareLogForSharing(logFile: LogFile, onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val tempDir = application.externalCacheDir?.resolve("logs")?.apply { mkdirs() }
                        ?: error("External cache dir is not available")
                    val tempFile = File(tempDir, logFile.fileName)

                    logFile.file.copyTo(tempFile, overwrite = true)

                    val contentUri = FileProvider.getUriForFile(
                        application,
                        Env.FILE_PROVIDER_AUTHORITY,
                        tempFile
                    )

                    withContext(Dispatchers.Main) {
                        onReady(contentUri)
                    }
                }
            }.onFailure {
                Logger.error("Error preparing file for sharing", it, context = TAG)
            }
        }
    }

    fun deleteAllLogs() {
        viewModelScope.launch {
            Logger.reset()
            loadLogs()
        }
    }
}
