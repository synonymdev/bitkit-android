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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.env.Env
import to.bitkit.utils.Logger
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import javax.inject.Inject

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val application: Application,
) : AndroidViewModel(application) {
    private val _logs = MutableStateFlow<List<LogFile>>(emptyList())
    val logs: StateFlow<List<LogFile>> = _logs.asStateFlow()

    private val _selectedLogContent = MutableStateFlow<List<String>>(emptyList())
    val selectedLogContent: StateFlow<List<String>> = _selectedLogContent.asStateFlow()

    fun loadLogs() {
        viewModelScope.launch {
            try {
                val logDir = File(Env.logDir)
                if (!logDir.exists()) {
                    _logs.value = emptyList()
                    return@launch
                }

                val logFiles = logDir
                    .listFiles { file -> file.extension == "log" }
                    ?.map { file ->
                        val fileName = file.name
                        val components = fileName.split("_")

                        val serviceName = components.firstOrNull()
                            ?.let { c -> c.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
                            ?: "Unknown"
                        val timestamp = if (components.size >= 3) components[components.size - 2] else ""
                        val displayName = "$serviceName Log: $timestamp"

                        LogFile(
                            displayName = displayName,
                            file = file,
                        )
                    }
                    ?.sortedByDescending { it.file.lastModified() }
                    ?: emptyList()

                _logs.value = logFiles
            } catch (e: Exception) {
                _logs.value = emptyList()
                Logger.error("Failed to load logs", e)
            }
        }
    }

    fun loadLogContent(logFile: LogFile) {
        viewModelScope.launch {
            try {
                if (!logFile.file.exists()) {
                    _selectedLogContent.value = listOf("Log file not found")
                    return@launch
                }

                val lines = mutableListOf<String>()
                BufferedReader(FileReader(logFile.file)).use { reader ->
                    reader.forEachLine { line ->
                        lines.add(line.trim())
                    }
                }
                _selectedLogContent.value = lines
            } catch (e: Exception) {
                _selectedLogContent.value = listOf("Error loading log: ${e.message}")
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
