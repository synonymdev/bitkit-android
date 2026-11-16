package to.bitkit.models

enum class TransferType {
    TO_SPENDING,
    TO_SAVINGS,
    MANUAL_SETUP,
    FORCE_CLOSE,
    COOP_CLOSE,

    /** The Spark balance leaving on-chain without the Spark operators. */
    SPARK_EXIT;

    fun isToSavings() = this in listOf(TO_SAVINGS, COOP_CLOSE, FORCE_CLOSE, SPARK_EXIT)
    fun isToSpending() = this in listOf(TO_SPENDING, MANUAL_SETUP)
}
