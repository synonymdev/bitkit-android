package to.bitkit.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

sealed class WalletInitResult {
    data object Created : WalletInitResult()
    data object Restored : WalletInitResult()
    data class Failed(val error: Throwable) : WalletInitResult()
}

@Composable
fun WalletInitResultView(
    result: WalletInitResult,
    onButtonClick: () -> Unit,
) {
    val titleText1 = when (result) {
        is WalletInitResult.Created, is WalletInitResult.Restored -> "WALLET"
        is WalletInitResult.Failed -> "SPENDING BALANCE"
    }

    val titleText2 = when (result) {
        is WalletInitResult.Created -> "CREATED"
        is WalletInitResult.Restored -> "RESTORED"
        is WalletInitResult.Failed -> "ERROR"
    }

    val titleColor = when (result) {
        is WalletInitResult.Created, is WalletInitResult.Restored -> Colors.Green
        is WalletInitResult.Failed -> Colors.Red
    }

    val description = when (result) {
        is WalletInitResult.Created -> "Your new wallet is ready to use."
        is WalletInitResult.Restored -> "You have successfully restored your wallet from backup. Enjoy Bitkit!"
        is WalletInitResult.Failed -> "Bitkit restored your savings, but failed to restore your current spending balance (Lightning state) and wallet data."
    }

    val buttonText = when (result) {
        is WalletInitResult.Created, is WalletInitResult.Restored -> "Get Started"
        is WalletInitResult.Failed -> "Try Again"
    }

    val imageResource = when (result) {
        is WalletInitResult.Created, is WalletInitResult.Restored -> R.drawable.check
        is WalletInitResult.Failed -> R.drawable.cross
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 32.dp),
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Column {
            Display(titleText1)
            Display(
                text = titleText2,
                color = titleColor,
                modifier = Modifier.offset(y = (-8).dp)
            )
            BodyM(description, color = Colors.White80)
        }

        Spacer(modifier = Modifier.weight(1f))

        Image(
            painter = painterResource(id = imageResource),
            contentDescription = null,
            modifier = Modifier
                .size(256.dp)
                .align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onButtonClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = Colors.White16,
                disabledContainerColor = Color.Transparent,
                contentColor = Colors.White,
                disabledContentColor = Colors.White32,
            ),
            shape = RoundedCornerShape(30.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(
                text = buttonText,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
fun WalletInitResultViewRestoredPreview() {
    AppThemeSurface {
        WalletInitResultView(result = WalletInitResult.Restored) {}
    }
}

@Preview(showSystemUi = true)
@Composable
fun WalletInitResultViewErrorPreview() {
    AppThemeSurface {
        WalletInitResultView(result = WalletInitResult.Failed(Throwable("Something went wrong"))) {}
    }
}
