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
import javax.inject.Named

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

        @Provides
        fun provideQuickPayPaymentLookup(lightningRepo: LightningRepo): QuickPayPaymentLookup =
            QuickPayPaymentLookup { lightningRepo.listPaymentsOrNull()?.map { QuickPayReconcileRow(it) } }
    }
}
