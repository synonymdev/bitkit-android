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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.utils.Logger
import to.bitkit.utils.bip39Words

@Composable
fun ShowMnemonicScreen(
    onContinue: (seed: List<String>, bip39Passphrase: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val app = appViewModel ?: return
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var mnemonic by remember { mutableStateOf("") }
    var bip39Passphrase by remember { mutableStateOf("") }
    var showMnemonic by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            mnemonic = "" // Clear mnemonic from memory when leaving screen
            bip39Passphrase = ""
        }
    }

    ShowMnemonicContent(
        mnemonic = mnemonic,
        showMnemonic = showMnemonic,
        isLoading = isLoading,
        onDismiss = onDismiss,
        onRevealClick = {
            scope.launch {
                try {
                    isLoading = true
                    delay(200)
                    val loadedMnemonic = app.loadMnemonic()!!
                    val loadedPassphrase = app.loadBip39Passphrase()
                    mnemonic = loadedMnemonic
                    bip39Passphrase = loadedPassphrase
                    showMnemonic = true
                } catch (e: Throwable) {
                    Logger.error("Failed to load mnemonic", e)
                    app.toast(
                        type = Toast.ToastType.WARNING,
                        title = context.getString(R.string.security__mnemonic_error),
                        description = context.getString(R.string.security__mnemonic_error_description),
                    )
                }
            }
        },
        onCopyClick = {
            clipboard.setText(AnnotatedString(mnemonic))
        },
        onContinueClick = {
            onContinue(mnemonic.split(" "), bip39Passphrase)
        },
    )
}

@Composable
private fun ShowMnemonicContent(
    mnemonic: String,
    showMnemonic: Boolean,
    isLoading: Boolean,
    onDismiss: () -> Unit,
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

    val mnemonicWords = if (mnemonic.isNotEmpty()) mnemonic.split(" ") else emptyList()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

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
            .padding(horizontal = 32.dp)
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.security__mnemonic_your),
            onBack = onDismiss,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            AnimatedContent(
                targetState = showMnemonic,
                transitionSpec = { fadeIn(tween(300)).togetherWith(fadeOut(tween(300))) },
                label = "topText"
            ) { isRevealed ->
                BodyM(
                    text = if (isRevealed) {
                        stringResource(R.string.security__mnemonic_write).replace("{length}", "${mnemonicWords.size}")
                    } else {
                        stringResource(R.string.security__mnemonic_use)
                    },
                    color = Colors.White64,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(color = Colors.White10)
                        .clickable(enabled = showMnemonic && mnemonic.isNotEmpty(), onClick = onCopyClick)
                        .padding(horizontal = 32.dp, vertical = 32.dp)
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
                            isLoading = isLoading,
                            onClick = onRevealClick,
                            color = Colors.Black50,
                            modifier = Modifier.alpha(buttonAlpha)
                        )
                    }
                }
            }

            BodyS(
                text = stringResource(R.string.security__mnemonic_never_share).withAccent(accentColor = Colors.Brand),
                color = Colors.White64,
            )

            Spacer(modifier = Modifier.height(24.dp))

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onContinueClick,
                enabled = showMnemonic,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun MnemonicWordsGrid(
    actualWords: List<String>,
    showMnemonic: Boolean,
    blurRadius: Float,
) {
    val placeholderWords = remember { List(24) { "secret" } }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .blur(radius = blurRadius.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
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
            mnemonic = "",
            showMnemonic = false,
            isLoading = false,
            onDismiss = {},
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
            mnemonic = List(24) { bip39Words.random() }.joinToString(" "),
            showMnemonic = true,
            isLoading = false,
            onDismiss = {},
            onRevealClick = {},
            onCopyClick = {},
            onContinueClick = {},
        )
    }
}
