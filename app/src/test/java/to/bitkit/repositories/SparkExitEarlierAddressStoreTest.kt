package to.bitkit.repositories

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

class SparkExitEarlierAddressStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `concurrent writers through separate stores of one file keep every address`() {
        val file = File(tmp.root, "spark-exit-earlier.json")
        // A foreground balance read and an exit tick each build their own store over the same file.
        val stores = listOf(
            SparkExitEarlierAddressStore(file),
            SparkExitEarlierAddressStore(File(tmp.root, "./${file.name}")),
        )
        val count = 200
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val failures = mutableListOf<Throwable>()
        repeat(count) { i ->
            pool.execute {
                start.await()
                runCatching {
                    stores[i % stores.size].add(
                        SparkExitEarlierAddress(
                            address = "bcrt1q$i",
                            path = "m/86'/1'/0'/0/$i",
                            kind = SparkExitEarlierAddress.Kind.KEPT_CHANGE,
                            sats = i.toULong(),
                            atMillis = i.toLong(),
                        ),
                    )
                }.onFailure { synchronized(failures) { failures += it } }
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        assertEquals(emptyList(), failures)
        assertEquals((0 until count).map { "bcrt1q$it" }.toSet(), stores[0].load().map { it.address }.toSet())
    }
}
