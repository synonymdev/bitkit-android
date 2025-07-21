package to.bitkit.ui.screens.shop

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ShopWebViewScreen(
    onClose: () -> Unit,
    onBack: () -> Unit,
    page: String,
) {
    var isLoading by remember { mutableStateOf(true) }
    var webView: WebView? by remember { mutableStateOf(null) }

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.other__shop__discover__nav_title),
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
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                super.onReceivedError(view, request, error)
                                isLoading = false
                                onClose()
                            }
                        }
                        settings.javaScriptEnabled = true
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
                    onClose()
                }
            } ?: onClose()
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
            page = "esims",
        )
    }
}
