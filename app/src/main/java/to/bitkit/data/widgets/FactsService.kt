package to.bitkit.data.widgets

import to.bitkit.models.WidgetType
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days

@Singleton
class FactsService @Inject constructor() : WidgetService<List<String>> {

    private val facts = listOf<String>(
        "Satoshi Nakamoto mined more than 1M Bitcoin.",
        "You don't need permission to use Bitcoin.",
        "You don't need a bank account to use Bitcoin.",
        "Bitcoin is a public ledger.",
        "Bitcoin can use otherwise wasted energy.",
        "Priced in Bitcoin, products can become cheaper over time.",
        "Your node, your rules.",
        "Bitcoin does not discriminate.",
        "About 20% of Bitcoin may be lost forever.",
        "A Bitcoin faucet gave out 5 BTC per visitor.",
        "Every 210,000 blocks, mining rewards are cut in half.",
        "It takes about 10 minutes to mine a new block..",
        "The largest transaction was 500,000 bitcoin.",
        "Bitcoin is legal tender in El Salvador.",
        "Not your keys, not your coins.",
        "’Bitcoin’ is the network, ‘bitcoin’ is the currency.",
        "Bitcoin was not the first digital currency.",
        "Bitcoin was first created with 31,000 lines of code.",
        "Bitcoin does not have a CEO.",
        "Initially you could send Bitcoin to an IP address.",
        "Bitcoin did not always have a block size limit.",
        "The first Bitcoin purchase was for a pizza.",
        "May 22 is celebrated as Bitcoin Pizza Day.",
        "Somebody paid 10,000 bitcoins for pizza.",
        "The identity of Bitcoin's inventor is unknown.",
        "If you lose your keys, you lose your coins.",
        "Bitcoins don't grow on trees.",
        "There can only be 21 million bitcoins. ",
        "Bitcoins are created when a block is mined.",
        "One bitcoin is 100,000,000 satoshis.",
        "The smallest unit of Bitcoin is a “satoshi.”",
        "Bitcoins live on the blockchain, not in wallets.",
        "You can hold keys, but you cannot hold bitcoin.",
        "Private keys allow you to sign transactions.",
        "Public keys are used to create payment addresses.",
        "Satoshi Nakamoto wrote the Bitcoin whitepaper.",
        "Satoshi Nakamoto mined the 'genesis' block.",
        "The whitepaper was published Oct 31, 2008.",
        "The genesis block was mined Jan 3, 2009.",
        "It takes energy to mine a new Bitcoin block.",
        "Mining a block is solving a cryptographic puzzle.",
        "Mining is guessing numbers.",
        "The last Bitcoin will be mined in 2140.",
        "Bitcoin operates without central authority.",
        "No company controls Bitcoin.",
        "The block reward halves every four years.",
        "Bitcoin inflation rate declines over time.",
        "Bitcoin is censorship-resistant.",
        "The Bitcoin protocol is trustless.",
        "You can verify all bitcoin transactions.",
        "The Bitcoin network is open to anyone.",
        "Draft of Lightning white paper: Feb 2015.",
        "First Lightning payment: May 10, 2017.",
        "The Lightning protocol is a payment layer.",
        "Lightning enables instant bitcoin payments.",
        "Lightning channels are peer-to-peer.",
        "Full nodes store the entire transaction history.",
        "You can generate a Bitcoin address offline.",
        "Bitcoin is natively measured in integers.",
        "Technically there are no bitcoins, only sats.",
        "The genesis block reward is not spendable.",
        "You can count 1 day of blocks on 2 hands.",
        "There are enough sats for everyone.",
        "More computing power ≠ more bitcoin.",
        "Bitcoin doesn't need your personal info.",
        "Satoshi considered calling it Netcoin.",
    )

    override val widgetType = WidgetType.FACTS
    override val refreshInterval = 1.days

    override suspend fun fetchData(): Result<List<String>> = runCatching {
        return Result.success(facts)
    }.onFailure {
        Logger.warn(e = it, msg = "Failed to fetch facts", context = TAG)
    }

    companion object {
        private const val TAG = "FactsService"
    }
}
