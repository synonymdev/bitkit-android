package to.bitkit.data.dto.price

import kotlinx.serialization.Serializable

@Serializable
enum class GraphPeriod(val value: String) {
    ONE_DAY("1D"),
    ONE_WEEK("1W"),
    ONE_MONTH("1M"),
    ONE_YEAR("1Y")
}
