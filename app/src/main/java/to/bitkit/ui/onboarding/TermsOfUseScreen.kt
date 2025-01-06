package to.bitkit.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.R
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.shared.util.DarkModePreview
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.BodyMSB
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.Display

private val horizontalPadding = 32.dp

@Composable
fun TermsOfUseScreen(
    onNavigateToIntro: () -> Unit,
) {
    var termsAccepted by rememberSaveable { mutableStateOf(false) }
    var privacyAccepted by rememberSaveable { mutableStateOf(false) }
    Scaffold { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Scrolling Content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalPadding)
                        .verticalScroll(rememberScrollState())
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Display("BITKIT")
                    Display(
                        text = stringResource(R.string.tos_header),
                        color = Colors.Brand,
                        modifier = Modifier.offset(y = (-8).dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TosContent()
                    Spacer(modifier = Modifier.height(20.dp))
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(70.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.background,
                                ),
                            )
                        )
                )
            }
            // Footer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                CheckButton(
                    title = stringResource(R.string.tos_checkbox),
                    htmlText = stringResource(R.string.tos_checkbox_value),
                    isChecked = termsAccepted,
                    onCheckedChange = { termsAccepted = it },
                    modifier = Modifier.padding(horizontal = horizontalPadding)
                )
                CheckButton(
                    title = stringResource(R.string.pp_checkbox),
                    htmlText = stringResource(R.string.pp_checkbox_value),
                    isChecked = privacyAccepted,
                    onCheckedChange = { privacyAccepted = it },
                    modifier = Modifier.padding(horizontal = horizontalPadding)
                )

                Spacer(modifier = Modifier.height(24.dp))

                PrimaryButton(
                    text = "Continue",
                    onClick = onNavigateToIntro,
                    enabled = termsAccepted && privacyAccepted,
                    modifier = Modifier.padding(horizontal = horizontalPadding)
                )
            }
        }
    }
}

@Composable
private fun CheckButton(
    title: String,
    htmlText: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!isChecked) }
            .then(modifier)
    ) {
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                BodyMSB(title)
                Spacer(modifier = Modifier.height(4.dp))
                HtmlTextWithLink(htmlText)
            }
            Spacer(modifier = Modifier.width(8.dp))
            CheckmarkBox(isChecked)
        }
        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider()
    }
}

@Composable
private fun HtmlTextWithLink(htmlText: String) {
    Text(
        text = AnnotatedString.fromHtml(
            htmlString = htmlText,
            linkStyles = TextLinkStyles(style = SpanStyle(color = Colors.Brand)),
        ),
        color = MaterialTheme.colorScheme.secondary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    )
}

@Composable
private fun CheckmarkBox(isChecked: Boolean) {
    val borderColor = if (isChecked) Colors.Brand else Colors.White32
    val backgroundColor = if (isChecked) Color(0x52FF6600) else Colors.White10

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(size = 8.dp))
            .background(color = backgroundColor, shape = RoundedCornerShape(size = 8.dp))
            .size(32.dp)
    ) {
        if (isChecked) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Colors.Brand,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(22.dp)
            )
        }
    }
}

@DarkModePreview
@Composable
private fun TermsPreview() {
    AppThemeSurface {
        TermsOfUseScreen(
            onNavigateToIntro = {}
        )
    }
}
