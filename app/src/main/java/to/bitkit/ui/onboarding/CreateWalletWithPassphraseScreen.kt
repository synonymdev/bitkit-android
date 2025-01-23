package to.bitkit.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.R
import to.bitkit.ui.components.Display
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

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
        TopAppBar(
            title = {},
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Default.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            Image(
                painter = painterResource(id = R.drawable.padlock2),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(32.dp))
            Display(text = "SECURE WITH")
            Display(
                text = "PASSPHRASE",
                color = Colors.Brand,
                modifier = Modifier.offset(y = (-8).dp)
            )
            Text(
                text = "You can add a secret passphrase to the 12-word recovery phrase. If you do, make sure you don’t forget.",
                fontSize = 17.sp,
                lineHeight = 22.sp,
                color = Colors.White64,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(32.dp))
            OutlinedTextField(
                value = bip39Passphrase,
                onValueChange = { bip39Passphrase = it },
                placeholder = { Text("Passphrase") },
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
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = { onCreateClick(bip39Passphrase) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Colors.White16,
                    disabledContainerColor = Color.Transparent,
                    contentColor = Colors.White,
                    disabledContentColor = Colors.White32,
                ),
                enabled = bip39Passphrase.isNotBlank(),
                shape = RoundedCornerShape(30.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    text = "Create New Wallet",
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CreateWalletWithPassphraseScreenPreview() {
    AppThemeSurface {
        CreateWalletWithPassphraseScreen(
            onBackClick = {},
            onCreateClick = {},
        )
    }
}
