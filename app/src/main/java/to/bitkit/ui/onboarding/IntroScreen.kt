package to.bitkit.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
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
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.Display

@Composable
fun IntroScreen(
    onStartClick: () -> Unit,
    onSkipClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Image(
            painter = painterResource(id = R.drawable.figures),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 200.dp)
                    .padding(horizontal = 48.dp)
                    .fillMaxWidth()
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .padding(bottom = 16.dp)
                    .align(Alignment.BottomCenter)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Display("YOU CAN ₿")
                    Display(
                        text = "THE CHANGE",
                        color = Colors.Brand,
                        modifier = Modifier.offset(y = (-8).dp)
                    )
                    Text(
                        text = "Use Bitkit to pay anyone, anywhere, any time, and spend your bitcoin on the things you value in life.",
                        style = TextStyle(
                            fontSize = 17.sp,
                            lineHeight = 22.sp,
                            letterSpacing = 0.4.sp,
                            color = Colors.White64,
                        ),
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = onStartClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Colors.White16),
                        shape = RoundedCornerShape(30.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    ) {
                        Text(
                            text = "Get Started",
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                            color = Colors.White
                        )
                    }
                    OutlinedButton(
                        onClick = onSkipClick,
                        shape = RoundedCornerShape(30.dp),
                        border = BorderStroke(1.dp, Colors.White16),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    ) {
                        Text(
                            text = "Skip Intro",
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                            color = Colors.White80
                        )
                    }
                }
            }
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun IntroViewPreview() {
    AppThemeSurface {
        IntroScreen(
            onStartClick = {},
            onSkipClick = {})
    }
}
