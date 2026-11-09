package to.bitkit.test

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Funds and mines on the local regtest bitcoind from bitkit-docker. The wallet under test reaches
 * that chain through the stack's electrs in an `E2E=true` build (see `Env.electrumUrlForNetwork`).
 */
object LocalRegtest {
    private const val SATS_PER_BTC = 100_000_000uL

    private val rpcUrl: String
        get() = InstrumentationRegistry.getArguments().getString("bitcoinRpcUrl") ?: "http://10.0.2.2:43782"
    private val rpcAuth = Base64.encodeToString("polaruser:polarpass".toByteArray(), Base64.NO_WRAP)

    private suspend fun call(method: String, vararg params: Any): Any = withContext(Dispatchers.IO) {
        val connection = URL(rpcUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Basic $rpcAuth")
            connection.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject()
                .put("jsonrpc", "1.0")
                .put("id", method)
                .put("method", method)
                .put("params", JSONArray(params.toList()))
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val stream = if (connection.responseCode < 400) connection.inputStream else connection.errorStream
            val text = checkNotNull(stream) { "bitcoind $method failed: HTTP ${connection.responseCode}" }
                .bufferedReader().use { it.readText() }
            val response = runCatching { JSONObject(text) }
                .getOrElse { error("bitcoind $method failed: HTTP ${connection.responseCode} $text") }
            val error = response.optJSONObject("error")
            check(error == null) { "bitcoind $method failed: ${error?.optString("message")}" }
            response.get("result")
        } finally {
            connection.disconnect()
        }
    }

    /** Sends [amountSat] to [address] from the bitcoind wallet and returns the txid. */
    suspend fun deposit(address: String, amountSat: ULong): String {
        val whole = (amountSat / SATS_PER_BTC).toLong()
        val fraction = (amountSat % SATS_PER_BTC).toLong()
        val btc = "%d.%08d".format(Locale.ROOT, whole, fraction)
        return call("sendtoaddress", address, btc) as String
    }

    suspend fun mine(count: UInt = 1u) {
        val address = call("getnewaddress") as String
        call("generatetoaddress", count.toInt(), address)
    }
}
