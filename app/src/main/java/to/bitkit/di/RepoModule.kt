package to.bitkit.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.lightningdevkit.ldknode.Bolt11Invoice
import to.bitkit.repositories.AmountInputHandler
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.QuickPayInvoiceParser
import to.bitkit.repositories.QuickPayPaymentLookup
import to.bitkit.repositories.QuickPayReconcileRow
import to.bitkit.repositories.SparkRepo
import javax.inject.Named
import breez_sdk_spark.PaymentStatus as SparkPaymentStatus

@Module
@InstallIn(SingletonComponent::class)
abstract class RepoModule {

    @Suppress("unused")
    @Binds
    abstract fun bindAmountInputHandler(currencyRepo: CurrencyRepo): AmountInputHandler

    companion object {
        @Suppress("FunctionOnlyReturningConstant")
        @Provides
        @Named("enablePolling")
        fun provideEnablePolling(): Boolean = true

        @Provides
        fun provideQuickPayInvoiceParser(): QuickPayInvoiceParser = QuickPayInvoiceParser { bolt11 ->
            runCatching { Bolt11Invoice.fromStr(bolt11).paymentHash() }.getOrNull()
        }

        /**
         * Payments of both rails, one row per invoice for a payment either rail made, so an older failed channel
         * attempt never settles one a Spark retry still holds or paid. Null while either rail can't list them.
         */
        @Provides
        fun provideQuickPayPaymentLookup(lightningRepo: LightningRepo, sparkRepo: SparkRepo): QuickPayPaymentLookup =
            QuickPayPaymentLookup {
                val node = lightningRepo.listPaymentsOrNull()?.map { QuickPayReconcileRow(it) }
                    ?: return@QuickPayPaymentLookup null
                val spark = sparkRepo.lightningSendStatuses() ?: return@QuickPayPaymentLookup null
                val sparkRows = spark.map { (hash, status) ->
                    QuickPayReconcileRow(
                        paymentId = hash,
                        invoicePaymentHash = hash,
                        isOutboundBolt11 = true,
                        status = when (status) {
                            SparkPaymentStatus.COMPLETED -> QuickPayReconcileRow.Status.SUCCEEDED
                            SparkPaymentStatus.PENDING -> QuickPayReconcileRow.Status.PENDING
                            SparkPaymentStatus.FAILED -> QuickPayReconcileRow.Status.FAILED
                        },
                    )
                }
                val (sends, others) = (node + sparkRows).partition { it.isOutboundBolt11 }
                others + sends.groupBy { it.invoicePaymentHash }.values.map { rows ->
                    rows.maxBy { SETTLING_STATUSES.indexOf(it.status) }
                }
            }

        /** The order in which an outcome settles a payment both rails attempted, the last one winning. */
        private val SETTLING_STATUSES = listOf(
            QuickPayReconcileRow.Status.FAILED,
            QuickPayReconcileRow.Status.PENDING,
            QuickPayReconcileRow.Status.SUCCEEDED,
        )
    }
}
