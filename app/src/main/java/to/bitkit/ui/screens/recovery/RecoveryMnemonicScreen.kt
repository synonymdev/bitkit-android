package to.bitkit.ui.screens.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.MnemonicWordsGrid
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun RecoveryMnemonicScreen(
    onNavigateBack: () -> Unit,
    recoveryMnemonicViewModel: RecoveryMnemonicViewModel = hiltViewModel(),
) {
    val uiState by recoveryMnemonicViewModel.uiState.collectAsState()

    Content(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
    )
}

@Composable
private fun Content(
    uiState: RecoveryMnemonicUiState,
    onNavigateBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .screen()
    ) {
        AppTopBar(
            titleText = stringResource(R.string.security__mnemonic_phrase),
            onBackClick = onNavigateBack,
        )

        VerticalSpacer(16.dp)

        if (uiState.isLoading) {
            // Loading state
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            // Content state
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                BodyM(
                    text = stringResource(R.string.security__mnemonic_write).replace(
                        "{length}",
                        uiState.mnemonicWords.count().toString()
                    ),
                    color = Colors.White64
                )

                VerticalSpacer(16.dp)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(color = Colors.White10)
                        .padding(32.dp)
                        .testTag("backup_mnemonic_words_box")
                ) {
                    MnemonicWordsGrid(
                        actualWords = uiState.mnemonicWords,
                        showMnemonic = true,
                    )
                }

                // Passphrase section (if available)
                if (uiState.passphrase.isNotEmpty()) {
                    VerticalSpacer(32.dp)

                    Column {
                        BodyM(text = stringResource(R.string.security__pass_text), color = Colors.White64)

                        VerticalSpacer(16.dp)

                        BodyM(
                            text = stringResource(R.string.security__pass_recovery, uiState.passphrase)
                                .replace("{passphrase}", uiState.passphrase)
                                .withAccent(accentColor = Colors.White64),
                            color = Colors.White
                        )
                    }
                }

                FillHeight()

                VerticalSpacer(32.dp)

                PrimaryButton(
                    text = stringResource(R.string.common__back),
                    onClick = onNavigateBack,
                )

                VerticalSpacer(16.dp)
            }
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun LoadingPreview() {
    AppThemeSurface {
        Content(
            uiState = RecoveryMnemonicUiState(isLoading = true),
            onNavigateBack = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun ContentPreview12Words() {
    AppThemeSurface {
        Content(
            uiState = RecoveryMnemonicUiState(
                isLoading = false,
                mnemonicWords = listOf(
                    "abandon", "ability", "able", "about", "above", "absent",
                    "absorb", "abstract", "absurd", "abuse", "access", "accident",
                ),
                passphrase = "my_secret_passphrase"
            ),
            onNavigateBack = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun ContentPreview24Words() {
    AppThemeSurface {
        Content(
            uiState = RecoveryMnemonicUiState(
                isLoading = false,
                mnemonicWords = listOf(
                    "abandon", "ability", "able", "about", "above", "absent",
                    "absorb", "abstract", "absurd", "abuse", "access", "accident",
                    "account", "accuse", "achieve", "acid", "acoustic", "acquire",
                    "across", "act", "action", "actor", "actress", "actual"
                ),
                passphrase = "my_secret_passphrase"
            ),
            onNavigateBack = {},
        )
    }
}
