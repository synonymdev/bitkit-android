package to.bitkit.repositories

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.errors.IOException
import org.junit.Test
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SparkExitChainTest : BaseUnitTest() {
    private val hostA = "https://a.example/api"
    private val hostB = "https://b.example/api"

    private class Route(val url: String, val status: Int, val body: String)

    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()

    /** Answers each request with the first route whose URL it ends with; an unknown URL fails like a dead host. */
    private fun chain(vararg routes: Route, hosts: List<String> = listOf(hostA)): EsploraSparkExitChain {
        val pending = routes.toMutableList()
        val engine = MockEngine { request -> answer(request, pending) }
        // Like the app's shared client, which defaults every request to JSON.
        val client = HttpClient(engine) { defaultRequest { contentType(ContentType.Application.Json) } }
        return EsploraSparkExitChain(hosts, client)
    }

    private suspend fun MockRequestHandleScope.answer(
        request: HttpRequestData,
        pending: MutableList<Route>,
    ): HttpResponseData {
        requests += request
        bodies += String(request.body.toByteArray())
        val url = request.url.toString()
        val route = pending.firstOrNull { url.endsWith(it.url) } ?: throw IOException("unreachable '$url'")
        pending.remove(route)
        return respond(route.body, HttpStatusCode.fromValue(route.status))
    }

    @Test
    fun `sends a package as a raw json array of hex`() = test {
        val sut = chain(Route("a.example/api/v1/txs/package", 200, """{"package_msg":"success","tx-results":{}}"""))

        val outcome = sut.broadcast(SparkExitFixtures.tx("n1", cpfp = "03child"))

        assertEquals(SparkExitBroadcastOutcome.Accepted, outcome)
        assertEquals(HttpMethod.Post, requests.single().method)
        assertEquals("""["02n1","03child"]""", bodies.single())
        assertEquals(ContentType.Application.Json, requests.single().body.contentType?.withoutParameters())
    }

    @Test
    fun `sends a step without cpfp as plain hex`() = test {
        val sut = chain(Route("a.example/api/tx", 200, "s1"))

        val outcome = sut.broadcast(SparkExitFixtures.tx("s1", kind = breez_sdk_spark.RecoveryTxKind.SWEEP))

        assertEquals(SparkExitBroadcastOutcome.Accepted, outcome)
        assertEquals("02s1", bodies.single())
        assertEquals(ContentType.Text.Plain, requests.single().body.contentType?.withoutParameters())
        assertEquals(null, requests.single().headers["Content-Type"])
    }

    @Test
    fun `falls back to the unversioned package path then the next host`() = test {
        val sut = chain(
            Route("a.example/api/v1/txs/package", 404, "Not Found"),
            Route("a.example/api/txs/package", 404, "Not Found"),
            Route("b.example/api/v1/txs/package", 200, """{"package_msg":"success"}"""),
            hosts = listOf(hostA, hostB),
        )

        val outcome = sut.broadcast(SparkExitFixtures.tx("n1", cpfp = "03c"))

        assertEquals(SparkExitBroadcastOutcome.Accepted, outcome)
        assertEquals(listOf("a.example", "a.example", "b.example"), requests.map { it.url.host })
    }

    @Test
    fun `tries the next host after a rejection or an unavailable host`() = test {
        val rejected = chain(
            Route(
                "a.example/api/tx",
                400,
                """sendrawtransaction RPC error: {"code":-26,"message":"min relay fee not met"}"""
            ),
            Route("b.example/api/tx", 200, "s1"),
            hosts = listOf(hostA, hostB),
        )
        assertEquals(SparkExitBroadcastOutcome.Accepted, rejected.broadcast(SparkExitFixtures.tx("s1")))

        val limited = chain(
            Route("a.example/api/tx", 429, "Too Many Requests"),
            Route("b.example/api/tx", 200, "s1"),
            hosts = listOf(hostA, hostB),
        )
        assertEquals(SparkExitBroadcastOutcome.Accepted, limited.broadcast(SparkExitFixtures.tx("s1")))
    }

    @Test
    fun `a node refusal wins over an unavailable or unreachable host`() = test {
        val sut = chain(
            Route(
                "a.example/api/tx",
                400,
                """sendrawtransaction RPC error: {"code":-26,"message":"min relay fee not met"}"""
            ),
            Route("b.example/api/tx", 503, ""),
            hosts = listOf(hostA, hostB, "https://c.example/api"),
        )

        assertIs<SparkExitBroadcastOutcome.Rejected>(sut.broadcast(SparkExitFixtures.tx("s1")))
    }

    @Test
    fun `no reachable host is retried later`() = test {
        val outcome = chain(hosts = listOf(hostA)).broadcast(SparkExitFixtures.tx("s1"))

        assertEquals(SparkExitBroadcastOutcome.RetryLater("no chain host reachable"), outcome)
    }

    @Test
    fun `reads the tip, funding utxos and a transaction status`() = test {
        val sut = chain(
            Route("a.example/api/blocks/tip/height", 200, "3681536\n"),
            Route(
                "a.example/api/address/bcrt1qf/utxo",
                200,
                """[{"txid":"t","vout":1,"value":5000,""" +
                    """"status":{"confirmed":true,"block_height":7,"block_hash":"h"}}]""",
            ),
            Route("a.example/api/tx/t/status", 200, """{"confirmed":false}"""),
        )

        assertEquals(3_681_536u, sut.tipHeight())
        assertEquals(listOf(EsploraUtxo("t", 1u, 5_000uL, EsploraTxStatus(true, 7u))), sut.utxos("bcrt1qf"))
        assertEquals(EsploraTxStatus(false, null), sut.txStatus("t"))
    }

    @Test
    fun `classifies node answers`() {
        val failedPackage = """{"package_msg":"transaction failed","tx-results":"""
        val classifier = SparkExitBroadcastClassifier
        assertEquals(
            SparkExitBroadcastOutcome.Accepted,
            classifier.single(400, """sendrawtransaction RPC error: {"code":-27}"""),
        )
        assertEquals(
            SparkExitBroadcastOutcome.InputsMissing("""sendrawtransaction RPC error: {"code":-25}"""),
            classifier.single(400, """sendrawtransaction RPC error: {"code":-25}"""),
        )
        assertEquals(SparkExitBroadcastOutcome.RetryLater("HTTP 503"), classifier.single(503, ""))
        assertIs<SparkExitBroadcastOutcome.Rejected>(
            classifier.single(400, """{"code":-26,"message":"min relay fee not met"}""")
        )

        val missing = failedPackage + """{"w1":{"txid":"a","error":"bad-txns-inputs-missingorspent"},""" +
            """"w2":{"txid":"b","error":"bad-txns-inputs-missingorspent"}},"replaced-transactions":[]}"""
        assertIs<SparkExitBroadcastOutcome.InputsMissing>(classifier.pkg(200, missing))
        val known = failedPackage + """{"w1":{"txid":"a","error":"txn-already-in-mempool"},""" +
            """"w2":{"txid":"b"}}}"""
        assertEquals(SparkExitBroadcastOutcome.Accepted, classifier.pkg(200, known))
        val nonFinal = failedPackage + """{"w1":{"txid":"a","error":"non-BIP68-final"}}}"""
        assertIs<SparkExitBroadcastOutcome.NotMature>(classifier.pkg(200, nonFinal))
        // A child of an immature parent can fail with any error; the parent's timelock decides.
        val nonFinalParent = failedPackage + """{"w1":{"txid":"a","error":"non-BIP68-final"},""" +
            """"w2":{"txid":"b","error":"min relay fee not met"}}}"""
        assertIs<SparkExitBroadcastOutcome.NotMature>(classifier.pkg(200, nonFinalParent))
        val escaped = """{"error":"submitpackage RPC error: {\"code\":-25}"}"""
        assertEquals(SparkExitBroadcastOutcome.InputsMissing(escaped), classifier.pkg(400, escaped))
        // Missing inputs on the CPFP child alone say nothing about the step.
        val childMissing = failedPackage + """{"w1":{"txid":"parent"},""" +
            """"w2":{"txid":"child","error":"bad-txns-inputs-missingorspent"}}}"""
        assertIs<SparkExitBroadcastOutcome.RetryLater>(classifier.pkg(200, childMissing, parentTxid = "parent"))
        val parentMissing = failedPackage + """{"w1":{"txid":"parent","error":"bad-txns-inputs-missingorspent"}}}"""
        assertIs<SparkExitBroadcastOutcome.InputsMissing>(classifier.pkg(200, parentMissing, parentTxid = "parent"))
        // The step's own missing inputs decide over its child's error, in either order.
        val parentFirst = failedPackage + """{"w1":{"txid":"parent","error":"bad-txns-inputs-missingorspent"},""" +
            """"w2":{"txid":"child","error":"package-not-validated"}}}"""
        assertIs<SparkExitBroadcastOutcome.InputsMissing>(classifier.pkg(200, parentFirst, parentTxid = "parent"))
        val childFirst = failedPackage + """{"w2":{"txid":"child","error":"package-not-validated"},""" +
            """"w1":{"txid":"parent","error":"bad-txns-inputs-missingorspent"}}}"""
        assertIs<SparkExitBroadcastOutcome.InputsMissing>(classifier.pkg(200, childFirst, parentTxid = "parent"))
        val lowFee = failedPackage + """{"w1":{"txid":"a","error":"min relay fee not met"}}}"""
        assertIs<SparkExitBroadcastOutcome.Rejected>(classifier.pkg(200, lowFee))
    }
}
