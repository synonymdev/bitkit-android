package to.bitkit.ui.settings.backups

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ShowPassphraseScreen(
    seed: List<String>,
    bip39Passphrase: String,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    ShowPassphraseContent(
        seed = seed,
        bip39Passphrase = bip39Passphrase,
        onContinue = onContinue,
        onBack = onBack,
    )
}

@Composable
private fun ShowPassphraseContent(
    seed: List<String>,
    bip39Passphrase: String,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .padding(horizontal = 32.dp)
    ) {
        SheetTopBar(
            titleText = "BIP39 Passphrase",
            onBack = onBack,
        )

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            BodyM(
                text = "Your wallet also uses this BIP39 passphrase:",
                color = Colors.White64,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(32.dp))

            BodyM(
                text = "Passphrase: $bip39Passphrase",
                color = Colors.White,
            )

            Spacer(modifier = Modifier.weight(1f))

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        ShowPassphraseContent(
            seed = listOf("word1", "word2", "word3"),
            bip39Passphrase = "test passphrase",
            onContinue = {},
            onBack = {},
        )
    }
}
