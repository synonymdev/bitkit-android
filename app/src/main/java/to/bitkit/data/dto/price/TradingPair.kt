package to.bitkit.data.dto.price

enum class TradingPair(
    val displayName: String,
    val base: String,
    val quote: String,
    val symbol: String,
    val position: Int,
) {
    BTC_USD(displayName = "BTC/USD", base = "BTC", quote = "USD", symbol = "$", position = 0),
    BTC_EUR(displayName = "BTC/EUR", base = "BTC", quote = "EUR", symbol = "€", position = 1),
    BTC_GBP(displayName = "BTC/GBP", base = "BTC", quote = "GBP", symbol = "£", position = 2),
    BTC_JPY(displayName = "BTC/JPY", base = "BTC", quote = "JPY", symbol = "¥", position = 3);

    val ticker: String
        get() = "$base$quote"
}

fun String.displayNameToTradingPair() = TradingPair.entries.firstOrNull { it.displayName == this }
