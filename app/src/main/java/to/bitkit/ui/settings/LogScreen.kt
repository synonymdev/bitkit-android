package to.bitkit.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.Caption
import to.bitkit.ui.navigateToLogFile
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.LogViewModel

@Composable
fun LogScreen(
    navController: NavController,
    viewModel: LogViewModel = hiltViewModel(),
) {
    val logs by viewModel.logs.collectAsState()
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadLogs()
    }

    ScreenColumn {
        AppTopBar(
            titleText = "Log Files",
            onBackClick = { navController.popBackStack() },
            actions = {
                IconButton(
                    onClick = { showDeleteConfirmation = true },
                    enabled = logs.isNotEmpty()
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete logs",
                    )
                }
            }
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logs) { logFile ->
                ListItem(
                    headlineContent = {
                        BodyMSB(text = logFile.displayName)
                    },
                    supportingContent = {
                        Caption(
                            text = logFile.fileName,
                            color = Colors.White64,
                        )
                    },
                    modifier = Modifier.clickableAlpha {
                        navController.navigateToLogFile(logFile.fileName)
                    }
                )
                HorizontalDivider()
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            shape = MaterialTheme.shapes.large,
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete All Logs") },
            text = { Text("Are you sure you want to delete all log files? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAllLogs()
                        showDeleteConfirmation = false
                    }
                ) {
                    Text("Delete", color = Colors.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun LogContentScreen(
    navController: NavController,
    fileName: String,
    viewModel: LogViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val logs by viewModel.logs.collectAsState()
    val logContent by viewModel.selectedLogContent.collectAsState()
    var isLoading by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.loadLogs()
    }

    LaunchedEffect(logs, fileName) {
        isLoading = true
        logs.find { it.fileName == fileName }?.let {
            viewModel.loadLogContent(it)
        }
    }

    // Auto scroll to bottom when content changes
    LaunchedEffect(logContent) {
        if (logContent.isNotEmpty()) {
            isLoading = false
            listState.animateScrollToItem(logContent.size - 1)
        }
    }

    ScreenColumn {
        AppTopBar(
            titleText = logs.find { it.fileName == fileName }?.displayName ?: "Log Content",
            onBackClick = { navController.popBackStack() },
            actions = {
                IconButton(
                    onClick = {
                        logs.find { it.fileName == fileName }?.let { file ->
                            viewModel.prepareLogForSharing(file) { tempFile ->
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, tempFile)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share Log File"))
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                    )
                }
            }
        )

        Box(modifier = Modifier.fillMaxSize()) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp)
                ) {
                    items(logContent) { line ->
                        Text(
                            text = line,
                            color = when {
                                line.contains("ERROR", ignoreCase = true) -> Colors.Red
                                else -> Colors.Green
                            },
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
