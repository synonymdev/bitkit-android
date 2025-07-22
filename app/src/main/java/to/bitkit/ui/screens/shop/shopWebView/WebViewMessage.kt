package to.bitkit.ui.screens.shop.shopWebView

import kotlinx.serialization.Serializable

@Serializable
data class WebViewMessage(
    val event: String,
    val paymentUri: String? = null
)
