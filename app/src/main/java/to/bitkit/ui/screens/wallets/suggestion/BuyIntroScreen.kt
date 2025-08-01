package to.bitkit.ui.screens.wallets.suggestion

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.models.Suggestion
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.wallets.HomeViewModel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun BuyIntroScreen(
    onBackClick: () -> Unit,
    homeViewModel: HomeViewModel = hiltViewModel()
) {
    BuyIntroContent(
        onBackClick = onBackClick,
        removeSuggestion = {
            homeViewModel.removeSuggestion(Suggestion.BUY)
        }
    )
}

@Composable
fun BuyIntroContent(
    onBackClick: () -> Unit,
    removeSuggestion: () -> Unit,
) {
    val context = LocalContext.current

    ScreenColumn {

        AppTopBar(
            titleText = "",
            onBackClick = onBackClick,
        )

        Column(
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.bitcoin_emboss),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            Display(
                text = stringResource(R.string.other__buy_header).withAccent(accentColor = Colors.Brand),
                color = Colors.White
            )
            Spacer(Modifier.height(8.dp))
            BodyM(text = stringResource(R.string.other__buy_text), color = Colors.White64)
            Spacer(Modifier.height(32.dp))
            PrimaryButton(
                text = stringResource(R.string.other__buy_button),
                onClick = {
                    removeSuggestion()
                    val intent = Intent(Intent.ACTION_VIEW, Env.EXCHANGES_URL.toUri())
                    context.startActivity(intent)
                }
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BuyIntroContent(onBackClick = {}, removeSuggestion = {})
    }
}
