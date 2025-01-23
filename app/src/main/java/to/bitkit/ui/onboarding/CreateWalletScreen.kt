package to.bitkit.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.R
import to.bitkit.ui.components.Display
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.Display

@Composable
fun CreateWalletScreen(
    onCreateClick: () -> Unit,
    onRestoreClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
    ) {
        Image(
            painter = painterResource(id = R.drawable.wallet),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .padding(top = 170.dp)
                .fillMaxWidth()
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(.325f)
                .align(Alignment.BottomCenter),
        ) {
            Display(text = "YOUR KEYS,")
            Display(
                text = "YOUR COINS",
                color = Colors.Brand,
                modifier = Modifier.offset(y = (-8).dp)
            )
            Text(
                text = "Let's create your wallet. Please be aware that Bitkit is mobile software.",
                fontSize = 17.sp,
                lineHeight = 22.sp,
                color = Colors.White64,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Don't store all your money in Bitkit.",
                fontSize = 17.sp,
                lineHeight = 22.sp,
                color = Colors.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onCreateClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Colors.White16),
                    shape = RoundedCornerShape(30.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                ) {
                    Text(
                        text = "New Wallet",
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        color = Colors.White
                    )
                }
                OutlinedButton(
                    onClick = onRestoreClick,
                    shape = RoundedCornerShape(30.dp),
                    border = BorderStroke(1.dp, Colors.White16),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                ) {
                    Text(
                        text = "Restore",
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        color = Colors.White80
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CreateWalletScreenPreview() {
    AppThemeSurface {
        CreateWalletScreen(
            onCreateClick = {},
            onRestoreClick = {}
        )
    }
}
