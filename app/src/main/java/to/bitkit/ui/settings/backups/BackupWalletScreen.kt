package to.bitkit.ui.settings.backups

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.ui.appViewModel
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.secondaryColor
import to.bitkit.utils.Logger

@Composable
fun BackupWalletScreen(
    navController: NavController,
) {
    ScreenColumn {
        AppTopBar(stringResource(R.string.title_backup_wallet), onBackClick = { navController.popBackStack() })
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            val app = appViewModel ?: return@Column

            var mnemonic by remember { mutableStateOf("") }
            var showMnemonic by remember { mutableStateOf(false) }
            val clipboard = LocalClipboardManager.current
            val scope = rememberCoroutineScope()

            val mnemonicWords = mnemonic.split(" ")
            val columnLength = mnemonicWords.size / 2

            Column {
                if (showMnemonic) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Write down these ${mnemonicWords.size} words in the right order and store them in a safe place.",
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                clipboard.setText(AnnotatedString(mnemonic))
                            }
                            .background(
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f),
                                shape = MaterialTheme.shapes.medium,
                            )
                            .padding(16.dp)
                    ) {
                        // First Column
                        Column(Modifier.weight(1f)) {
                            mnemonicWords.take(columnLength).forEachIndexed { index, word ->
                                Row(Modifier.padding(vertical = 4.dp)) {
                                    Text(
                                        text = "${index + 1}.",
                                        color = secondaryColor,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = word)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.weight(0.6f))
                        // Second Column
                        Column(Modifier.weight(1f)) {
                            mnemonicWords.drop(columnLength).forEachIndexed { index, word ->
                                Row(Modifier.padding(vertical = 4.dp)) {
                                    Text(
                                        text = "${columnLength + index + 1}.",
                                        color = secondaryColor,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = word)
                                }
                            }
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    mnemonic = app.loadMnemonic()!!
                                    showMnemonic = true
                                } catch (e: Exception) {
                                    Logger.error("Failed to load mnemonic", e)
                                    app.toast(
                                        type = Toast.ToastType.ERROR,
                                        title = "Error",
                                        description = "Could not retrieve backup phrase",
                                    )
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Tap To Reveal")
                    }
                }
            }
        }
    }
}
