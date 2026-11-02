package to.bitkit.test

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.ConfirmationStatus
import org.lightningdevkit.ldknode.PaymentKind
import to.bitkit.services.LightningService
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

suspend fun LightningService.awaitSpendableDeposits(
    deposits: Map<String, ULong>,
    timeout: Duration = 2.minutes,
) {
    require(deposits.isNotEmpty() && deposits.all { it.key.isNotBlank() && it.value > 0uL })
    var observation = "No wallet sync completed"
    val ready = withTimeoutOrNull(timeout) {
        var spendable: Boolean
        do {
            sync()
            val outputs = listSpendableOutputs().getOrThrow()
            val received = deposits.mapValues { (txid, _) ->
                outputs.filter { it.outpoint.txid == txid }.sumOf { it.valueSats }
            }
            val confirmed = listPayments().orEmpty().mapNotNull {
                (it.kind as? PaymentKind.Onchain)?.takeIf { payment ->
                    payment.status is ConfirmationStatus.Confirmed
                }?.txid
            }.toSet()
            observation = "walletTip=${status?.currentBestBlock}, balances=$balances, " +
                "spendableDeposits=$received, confirmedDeposits=${confirmed.intersect(deposits.keys)}, " +
                "spendableOutpoints=${outputs.map { it.outpoint }}"
            spendable = received == deposits && confirmed.containsAll(deposits.keys)
            if (!spendable) delay(2.seconds)
        } while (!spendable)
        true
    }
    check(ready == true) { "Timed out after $timeout waiting for spendable deposits $deposits; $observation" }
}
