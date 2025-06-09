package to.bitkit.data.dto.price

data class PriceWidgetData(
    val name: String,
    val change: Change,
    val price: String,
    val pastValues: List<Double>
)
