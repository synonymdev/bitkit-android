package to.bitkit.ui.settings.backups

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.EaseOutQuart
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.utils.bip39Words

@Composable
fun ShowMnemonicScreen(
    uiState: BackupContract.UiState,
    onRevealClick: () -> Unit,
    onContinueClick: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val mnemonicWords = remember(uiState.bip39Mnemonic) {
        uiState.bip39Mnemonic.split(" ").filter { it.isNotBlank() }
    }

    ShowMnemonicContent(
        mnemonic = uiState.bip39Mnemonic,
        mnemonicWords = mnemonicWords,
        showMnemonic = uiState.showMnemonic,
        onRevealClick = onRevealClick,
        onCopyClick = {
            clipboard.setText(AnnotatedString(uiState.bip39Mnemonic))
        },
        onContinueClick = onContinueClick,
    )
}

@Composable
private fun ShowMnemonicContent(
    mnemonic: String,
    mnemonicWords: List<String>,
    showMnemonic: Boolean,
    onRevealClick: () -> Unit,
    onCopyClick: () -> Unit,
    onContinueClick: () -> Unit,
) {
    val blurRadius by animateFloatAsState(
        targetValue = if (showMnemonic) 0f else 10f,
        animationSpec = tween(durationMillis = 800, easing = EaseOutQuart),
        label = "blurRadius"
    )

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
            delay(300) // Wait for the animation to start
            scope.launch {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }
    }

    Column(
        modifier = Modifier
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
                        blurRadius = blurRadius,
                    )
                }

                if (buttonAlpha > 0f) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .matchParentSize()
                    ) {
                        PrimaryButton(
                            text = stringResource(R.string.security__mnemonic_reveal),
                            fullWidth = false,
                            onClick = onRevealClick,
                            color = Colors.Black50,
                            modifier = Modifier
                                .alpha(buttonAlpha)
                                .testTag("backup_reveal_mnemonic_button")
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
                modifier = Modifier.testTag("backup_show_mnemonic_continue_button")
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MnemonicWordsGrid(
    actualWords: List<String>,
    showMnemonic: Boolean,
    blurRadius: Float,
) {
    val placeholderWords = remember(actualWords) { List(actualWords.size) { "secret" } }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .blur(radius = blurRadius.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            .alpha(1f - blurRadius * 0.075f)
    ) {
        Crossfade(
            targetState = showMnemonic,
            animationSpec = tween(durationMillis = 600),
            label = "mnemonicCrossfade"
        ) { isRevealed ->
            val wordsToShow = if (isRevealed && actualWords.isNotEmpty()) actualWords else placeholderWords

            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    wordsToShow.take(wordsToShow.size / 2).forEachIndexed { index, word ->
                        WordItem(
                            number = index + 1,
                            word = word
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    wordsToShow.drop(wordsToShow.size / 2).forEachIndexed { index, word ->
                        WordItem(
                            number = wordsToShow.size / 2 + index + 1,
                            word = word
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WordItem(
    number: Int,
    word: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BodyMSB(text = "$number.", color = Colors.White64)
        Spacer(modifier = Modifier.width(8.dp))
        BodyMSB(text = word, color = Colors.White)
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        ShowMnemonicContent(
            mnemonic = bip39Words.take(24).joinToString(" "),
            mnemonicWords = bip39Words.take(24),
            showMnemonic = false,
            onRevealClick = {},
            onCopyClick = {},
            onContinueClick = {},
        )
    }
}

@Preview
@Composable
private fun PreviewShown() {
    AppThemeSurface {
        ShowMnemonicContent(
            mnemonic = bip39Words.take(24).joinToString(" "),
            mnemonicWords = bip39Words.take(24),
            showMnemonic = true,
            onRevealClick = {},
            onCopyClick = {},
            onContinueClick = {},
        )
    }
}

@Preview
@Composable
private fun Preview12Words() {
    AppThemeSurface {
        ShowMnemonicContent(
            mnemonic = bip39Words.take(12).joinToString(" "),
            mnemonicWords = bip39Words.take(12),
            showMnemonic = true,
            onRevealClick = {},
            onCopyClick = {},
            onContinueClick = {},
        )
    }
}
