package to.bitkit.ui.utils

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Configures WebView settings for basic web content display
 */
@SuppressLint("SetJavaScriptEnabled")
fun WebView.configureForBasicWebContent() {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowContentAccess = true
        allowFileAccess = false
        allowUniversalAccessFromFileURLs = false
        allowFileAccessFromFileURLs = false
        // Disable mixed content for security
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // Enable zoom controls for map
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
    }
}
