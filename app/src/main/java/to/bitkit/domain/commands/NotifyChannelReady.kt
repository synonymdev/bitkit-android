package to.bitkit.domain.commands

import org.lightningdevkit.ldknode.Event
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NotificationDetails

sealed interface NotifyChannelReady {

    data class Command(
        val event: Event.ChannelReady,
        val includeNotification: Boolean = false,
    ) : NotifyChannelReady

    sealed interface Result : NotifyChannelReady {
        data class ShowSheet(
            val sheet: NewTransactionSheetDetails,
        ) : Result

        data class ShowNotification(
            val sheet: NewTransactionSheetDetails,
            val notification: NotificationDetails,
        ) : Result

        data object Skip : Result

        data object Duplicate : Result
    }
}
