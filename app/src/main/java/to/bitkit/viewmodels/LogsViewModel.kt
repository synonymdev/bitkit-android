package to.bitkit.viewmodels

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.env.Env
import to.bitkit.repositories.LogsRepo
import to.bitkit.utils.Logger
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import javax.inject.Inject

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val application: Application,
    private val logsRepo: LogsRepo
) : AndroidViewModel(application) {
    private val _logs = MutableStateFlow<List<LogFile>>(emptyList())
    val logs: StateFlow<List<LogFile>> = _logs.asStateFlow()

    private val _selectedLogContent = MutableStateFlow<List<String>>(emptyList())
    val selectedLogContent: StateFlow<List<String>> = _selectedLogContent.asStateFlow()

    fun loadLogs() {
        viewModelScope.launch {
            logsRepo.getLogs()
                .onSuccess { logList ->
                    _logs.update { logList }
                }.onFailure { e ->
                    Logger.error("Failed to load logs", e)
                    _logs.update { emptyList() }
                }
        }
    }

    fun loadLogContent(logFile: LogFile) {
        viewModelScope.launch {
            logsRepo.loadLogContent(logFile)
                .onSuccess { content ->
                    _selectedLogContent.update { content }
                }
                .onFailure { e ->
                    _selectedLogContent.update { listOf("Log file not found") }
                    Logger.error("Failed to load log content", e)
                }
        }
    }

    fun prepareLogForSharing(logFile: LogFile, onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            try {
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
            } catch (e: Exception) {
                Logger.error("Error preparing file for sharing", e)
            }
        }
    }

    fun deleteAllLogs() {
        viewModelScope.launch {
            try {
                val logDir = File(Env.logDir)
                logDir.listFiles { file ->
                    file.extension == "log"
                }?.forEach { file ->
                    file.delete()
                }
                loadLogs()
            } catch (e: Exception) {
                Logger.error("Failed to delete logs", e)
            }
        }
    }
}

data class LogFile(
    val displayName: String,
    val file: File,
) {
    val fileName: String get() = file.name
}
