package to.bitkit.data.dto.price

enum class TradingPair(
    val displayName: String,
    val base: String,
    val quote: String,
    val symbol: String
) {
    BTC_USD("BTC/USD", "BTC", "USD", "$"),
    BTC_EUR("BTC/EUR", "BTC", "EUR", "€"),
    BTC_GBP("BTC/GBP", "BTC", "GBP", "£"),
    BTC_JPY("BTC/JPY", "BTC", "JPY", "¥");

    val ticker: String
        get() = "$base$quote"
}

fun String.displayNameToTradingPair() = TradingPair.entries.firstOrNull { it.displayName == this }
