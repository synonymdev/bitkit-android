package to.bitkit.ui.screens.shop.shopWebView

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.utils.configureForBasicWebContent


@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun ShopWebViewScreen(
    onClose: () -> Unit,
    onBack: () -> Unit,
    onPaymentIntent: (String) -> Unit,
    page: String,
    title: String,
) {
    var isLoading by remember { mutableStateOf(true) }
    var webView: WebView? by remember { mutableStateOf(null) }

    val webViewInterface = remember { ShopWebViewInterface(onPaymentIntent) }
    val webViewClient = remember {
        ShopWebViewClient(
            onLoadingStateChanged = { loading -> isLoading = loading },
            onError = onClose
        )
    }

    ScreenColumn {
        AppTopBar(
            titleText = "${stringResource(R.string.other__shop__discover__nav_title)} $title",
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )

                        this.webViewClient = webViewClient
                        configureForBasicWebContent()
                        addJavascriptInterface(webViewInterface, "Android")
                        loadUrl(Env.buildBitrefillUri(page = page))
                        webView = this
                    }
                },
            )

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }

        BackHandler {
            webView?.let {
                if (it.canGoBack()) {
                    it.goBack()
                } else {
                    onBack()
                }
            } ?: onBack()
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        ShopWebViewScreen(
            onClose = {},
            onBack = {},
            onPaymentIntent = { uri ->
            },
            page = "esims",
            title = "Gift Cards"
        )
    }
}
