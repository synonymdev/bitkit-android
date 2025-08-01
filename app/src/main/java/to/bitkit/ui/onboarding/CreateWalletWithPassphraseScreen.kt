package to.bitkit.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults.MediumAppBarCollapsedHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.HighlightLabel
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.mainRectHeight
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateWalletWithPassphraseScreen(
    onBackClick: () -> Unit,
    onCreateClick: (passphrase: String) -> Unit,
) {
    var bip39Passphrase by remember { mutableStateOf("") }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = stringResource(R.string.common__back),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                modifier = Modifier.weight(1f)
            )

            HighlightLabel(
                stringResource(R.string.onboarding__advanced).uppercase(),
                Modifier
                    .padding(top = MediumAppBarCollapsedHeight / 2 - (mainRectHeight / 2))
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(MediumAppBarCollapsedHeight))
            Image(
                painter = painterResource(id = R.drawable.padlock2),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(32.dp))
            Display(text = stringResource(R.string.onboarding__passphrase_header).withAccent())
            Spacer(modifier = Modifier.height(8.dp))
            BodyM(
                text = stringResource(R.string.onboarding__passphrase_text),
                color = Colors.White64,
            )
            Spacer(modifier = Modifier.height(32.dp))
            OutlinedTextField(
                value = bip39Passphrase,
                onValueChange = { bip39Passphrase = it },
                placeholder = { Text(text = stringResource(R.string.onboarding__passphrase)) },
                shape = RoundedCornerShape(8.dp),
                colors = AppTextFieldDefaults.semiTransparent,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.None,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .testTag("PassphraseInput")
            )
            Spacer(modifier = Modifier.height(32.dp))
            PrimaryButton(
                text = stringResource(R.string.onboarding__create_new_wallet),
                onClick = { onCreateClick(bip39Passphrase) },
                enabled = bip39Passphrase.isNotBlank(),
                modifier = Modifier.testTag("CreateNewWallet")
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun CreateWalletWithPassphraseScreenPreview() {
    AppThemeSurface {
        CreateWalletWithPassphraseScreen(
            onBackClick = {},
            onCreateClick = {},
        )
    }
}
