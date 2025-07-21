package to.bitkit.ui.screens.shop

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.utils.Logger

@Serializable
data class WebViewMessage(
    val event: String,
    @SerialName("paymentUri")
    val paymentUri: String? = null
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ShopWebViewScreen(
    onClose: () -> Unit,
    onBack: () -> Unit,
    onPaymentIntent: (String) -> Unit,
    page: String,
) {
    var isLoading by remember { mutableStateOf(true) }
    var webView: WebView? by remember { mutableStateOf(null) }

    // Create a JavaScript interface for message handling
    class WebViewInterface {
        @JavascriptInterface
        fun postMessage(message: String) {
            try {
                val json = Json { ignoreUnknownKeys = true }
                val data = json.decodeFromString<WebViewMessage>(message)

                Log.d("WebView", "Received message: $message")

                when (data.event) {
                    "payment_intent" -> {
                        data.paymentUri?.let { uri ->
                            onPaymentIntent(uri)
                        }
                    }
                    // Add more event types as needed
                    else -> {
                        Log.d("WebView", "Unknown event type: ${data.event}")
                    }
                }
            } catch (e: Exception) {
                Log.e("WebView", "Error parsing message: $message", e)
            }
        }
    }

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

                                // Inject JavaScript to bridge postMessage to Android
                                view?.evaluateJavascript("""
                                    window.ReactNativeWebView = {
                                        postMessage: function(data) {
                                            Android.postMessage(data);
                                        }
                                    };

                                    // Override the default postMessage if it exists
                                    if (window.postMessage) {
                                        window.originalPostMessage = window.postMessage;
                                        window.postMessage = function(data) {
                                            if (typeof data === 'string') {
                                                Android.postMessage(data);
                                            } else {
                                                Android.postMessage(JSON.stringify(data));
                                            }
                                        };
                                    }
                                """.trimIndent(), null)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                super.onReceivedError(view, request, error)
                                Logger.warn("Error: ${error?.description}, Code: ${error?.errorCode}, URL: ${request?.url}", context = "ShopWebViewScreen")
                                isLoading = false

                                error?.let {
                                    if (it.errorCode == WebViewClient.ERROR_HOST_LOOKUP ||
                                        it.errorCode == WebViewClient.ERROR_CONNECT ||
                                        it.errorCode == WebViewClient.ERROR_TIMEOUT ||
                                        it.errorCode == WebViewClient.ERROR_FILE_NOT_FOUND) {
                                        onClose()
                                    }
                                }
                            }
                        }

                        // Configure WebView settings
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowContentAccess = true
                            allowFileAccess = false
                        }

                        addJavascriptInterface(WebViewInterface(), "Android")

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
            onPaymentIntent = { uri ->
            },
            page = "esims",
        )
    }
}
