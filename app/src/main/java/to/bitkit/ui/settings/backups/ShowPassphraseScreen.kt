package to.bitkit.ui.settings.backups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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

@Composable
fun ShowPassphraseScreen(
    uiState: BackupContract.UiState,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    ShowPassphraseContent(
        bip39Passphrase = uiState.bip39Passphrase,
        onContinue = onContinue,
        onBack = onBack,
    )
}

@Composable
private fun ShowPassphraseContent(
    bip39Passphrase: String,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("backup_show_passphrase_screen")
    ) {
        SheetTopBar(stringResource(R.string.security__pass_your), onBack = onBack)
        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
        ) {
            BodyM(
                text = stringResource(R.string.security__pass_text),
                color = Colors.White64,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(Colors.White10)
                    .heightIn(min = 235.dp)
                    .padding(32.dp)
            ) {
                BodyMSB(
                    text = stringResource(R.string.security__pass),
                    color = Colors.White64,
                )

                Spacer(modifier = Modifier.height(8.dp))

                BodyMSB(
                    text = bip39Passphrase,
                    color = Colors.White,
                    modifier = Modifier.testTag("backup_passphrase_text")
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
            BodyS(
                text = stringResource(R.string.security__pass_never_share).withAccent(accentColor = Colors.Brand),
                color = Colors.White64,
            )

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(24.dp))

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onContinue,
                modifier = Modifier.testTag("backup_show_passphrase_continue_button")
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        ShowPassphraseContent(
            bip39Passphrase = "mypassphrase",
            onContinue = {},
            onBack = {},
        )
    }
}
