package to.bitkit.domain.commands

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.R
import to.bitkit.di.IoDispatcher
import to.bitkit.models.NotificationDetails
import to.bitkit.repositories.PendingPaymentRepo
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotifyPendingPaymentResolvedHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val pendingPaymentRepo: PendingPaymentRepo,
) {
    companion object {
        const val TAG = "NotifyPendingPaymentResolvedHandler"
    }

    suspend operator fun invoke(
        command: NotifyPendingPaymentResolved.Command,
    ): Result<NotifyPendingPaymentResolved.Result> = withContext(ioDispatcher) {
        runCatching {
            if (!pendingPaymentRepo.isPending(command.paymentHash)) {
                return@runCatching NotifyPendingPaymentResolved.Result.Skip
            }
            val notification = buildNotificationContent(command)
            NotifyPendingPaymentResolved.Result.ShowNotification(notification)
        }.onFailure {
            Logger.error("Failed to process pending payment notification", e = it, context = TAG)
        }
    }

    private fun buildNotificationContent(
        command: NotifyPendingPaymentResolved.Command,
    ) = when (command) {
        is NotifyPendingPaymentResolved.Command.Success -> NotificationDetails(
            title = context.getString(R.string.wallet__toast_payment_sent_title),
            body = context.getString(R.string.wallet__toast_payment_sent_description),
        )
        is NotifyPendingPaymentResolved.Command.Failure -> NotificationDetails(
            title = context.getString(R.string.wallet__toast_payment_failed_title),
            body = context.getString(R.string.wallet__toast_payment_failed_description),
        )
    }
}
