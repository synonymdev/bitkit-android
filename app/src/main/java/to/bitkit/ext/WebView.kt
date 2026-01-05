package to.bitkit.ext

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView

fun WebView.configureForBasicWebContent() {
    settings.apply {
        @SuppressLint("SetJavaScriptEnabled")
        javaScriptEnabled = true
        domStorageEnabled = true
        allowContentAccess = true
        allowFileAccess = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        // Disable mixed content for security
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // Enable zoom controls for map
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
    }
}
