package to.bitkit.ui.settings.backups

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.setClipboardText
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.MnemonicWordsGrid
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.TRANSITION_SCREEN_MS
import to.bitkit.ui.utils.withAccent

@Composable
fun ShowMnemonicScreen(
    uiState: BackupContract.UiState,
    onRevealClick: () -> Unit,
    onContinueClick: () -> Unit,
) {
    val context = LocalContext.current
    ShowMnemonicContent(
        mnemonic = uiState.bip39Mnemonic,
        showMnemonic = uiState.showMnemonic,
        onRevealClick = onRevealClick,
        onCopyClick = {
            context.setClipboardText(uiState.bip39Mnemonic)
        },
        onContinueClick = onContinueClick,
    )
}

@Composable
private fun ShowMnemonicContent(
    mnemonic: String,
    showMnemonic: Boolean,
    onRevealClick: () -> Unit,
    onCopyClick: () -> Unit,
    onContinueClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mnemonicWords = remember(mnemonic) { mnemonic.split(" ").filter { it.isNotBlank() } }
    val buttonAlpha by animateFloatAsState(
        targetValue = if (showMnemonic) 0f else 1f,
        animationSpec = tween(durationMillis = 400),
        label = "buttonAlpha"
    )

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    val wordsCount = mnemonicWords.size

    // Scroll to bottom when mnemonic is revealed
    LaunchedEffect(showMnemonic) {
        if (showMnemonic) {
            delay(TRANSITION_SCREEN_MS) // Wait for the animation to start
            scope.launch {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("backup_show_mnemonic_screen")
    ) {
        SheetTopBar(stringResource(R.string.security__mnemonic_your))
        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
                .verticalScroll(scrollState)
        ) {
            AnimatedContent(
                targetState = showMnemonic,
                transitionSpec = { fadeIn(tween(300)).togetherWith(fadeOut(tween(300))) },
                label = "topText"
            ) { isRevealed ->
                BodyM(
                    text = when (isRevealed) {
                        true -> stringResource(R.string.security__mnemonic_write).replace("{length}", "$wordsCount")
                        else -> stringResource(R.string.security__mnemonic_use).replace("12", "$wordsCount")
                    },
                    color = Colors.White64,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(color = Colors.White10)
                        .clickable(enabled = showMnemonic && mnemonic.isNotEmpty(), onClick = onCopyClick)
                        .padding(32.dp)
                        .testTag("backup_mnemonic_words_box")
                ) {
                    MnemonicWordsGrid(
                        actualWords = mnemonicWords,
                        showMnemonic = showMnemonic,
                    )
                }

                if (buttonAlpha > 0f) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .matchParentSize()
                            .testTag("SeedContainer")
                            .semantics {
                                contentDescription = mnemonic
                            }
                    ) {
                        PrimaryButton(
                            text = stringResource(R.string.security__mnemonic_reveal),
                            fullWidth = false,
                            onClick = onRevealClick,
                            modifier = Modifier
                                .alpha(buttonAlpha)
                                .testTag("TapToReveal")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            BodyS(
                text = stringResource(R.string.security__mnemonic_never_share).withAccent(accentColor = Colors.Brand),
                color = Colors.White64,
            )

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(24.dp))

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onContinueClick,
                enabled = showMnemonic,
                modifier = Modifier.testTag("ContinueShowMnemonic")
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            var showMnemonic by remember { mutableStateOf(false) }
            ShowMnemonicContent(
                mnemonic = List(12) { "word${it + 1}" }.joinToString(" "),
                showMnemonic = showMnemonic,
                onRevealClick = { showMnemonic = !showMnemonic },
                onCopyClick = {},
                onContinueClick = {},
                modifier = Modifier.sheetHeight(SheetSize.MEDIUM, isModal = true)
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewShown() {
    AppThemeSurface {
        BottomSheetPreview {
            ShowMnemonicContent(
                mnemonic = List(12) { "word${it + 1}" }.joinToString(" "),
                showMnemonic = true,
                onRevealClick = {},
                onCopyClick = {},
                onContinueClick = {},
                modifier = Modifier.sheetHeight(SheetSize.MEDIUM, isModal = true)
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview24Words() {
    AppThemeSurface {
        BottomSheetPreview {
            ShowMnemonicContent(
                mnemonic = List(24) { "word${it + 1}" }.joinToString(" "),
                showMnemonic = true,
                onRevealClick = {},
                onCopyClick = {},
                onContinueClick = {},
                modifier = Modifier.sheetHeight(SheetSize.MEDIUM, isModal = true)
            )
        }
    }
}
