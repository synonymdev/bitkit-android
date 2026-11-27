// Shared by :app and :wear. Reads local secrets and settings, and lists the BuildConfig fields both modules' Spark
// wallets need. Script plugins don't see the Android Gradle Plugin's classes, so each module puts the fields itself.
import java.util.Base64
import java.util.Properties

val localProperties by lazy {
    Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { load(it) }
        }
    }
}

// macOS keychain item named like the key (stored through stdin, as the README shows), read at
// build time so local secrets need no plain copy in local.properties. Absent on Linux (CI) and when no item exists.
fun keychainProp(key: String): Provider<String> {
    val security = File("/usr/bin/security")
    if (!security.canExecute()) return provider { null }
    return providers.exec {
        commandLine(security.path, "find-generic-password", "-a", System.getProperty("user.name"), "-s", key, "-w")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }.filter { it.isNotEmpty() }
}

val KEYCHAIN_SOURCE = "the keychain item"

// Where localProp reads a key from, in the order it checks them.
fun localPropSources(key: String): List<Pair<String, Provider<String>>> = listOf(
    "the environment variable" to providers.environmentVariable(key),
    "the Gradle property" to providers.gradleProperty(key),
    "local.properties" to provider { localProperties.getProperty(key) },
    KEYCHAIN_SOURCE to keychainProp(key),
)

fun localProp(key: String): Provider<String> =
    localPropSources(key).map { it.second }.reduce { acc, prop -> acc.orElse(prop) }

fun envFlag(key: String, default: Boolean): Provider<String> {
    return providers.environmentVariable(key)
        .map { it.toBoolean().toString() }
        .orElse(default.toString())
}

// Total size a DER element declares in its header (tag, length bytes and content), or null when it is not a SEQUENCE.
fun derSize(der: ByteArray): Int? {
    if (der.size < 2 || der[0] != 0x30.toByte()) return null
    val first = der[1].toInt() and 0xff
    if (first < 0x80) return 2 + first
    val n = first and 0x7f
    if (n !in 1..3 || der.size < 2 + n) return null
    return 2 + n + (0 until n).fold(0) { acc, i -> acc shl 8 or (der[2 + i].toInt() and 0xff) }
}

// Breez's API key is a base64 DER certificate. A cut-short key (the bare `security … -w` prompt keeps only 128
// characters) turns Spark off at start, so a set key whose decoded size differs from the size its own DER header
// declares stops the build. The error names the source that supplied the key. Only lengths are reported.
// The key is read and checked only when the returned provider is, so a module that leaves the key out, as :wear does,
// builds whatever the local key looks like.
fun breezApiKey(): Provider<String> = provider { checkedBreezApiKey() }

fun checkedBreezApiKey(): String {
    val name = "BREEZ_API_KEY"
    val (source, raw) = localPropSources(name).firstNotNullOfOrNull { (src, prop) -> prop.orNull?.let { src to it } }
        ?: return ""
    val key = raw.trim()
    if (key.isEmpty()) return key
    val der = runCatching { Base64.getDecoder().decode(key) }.getOrNull()
    val declared = der?.let { derSize(it) }
    if (der != null && declared == der.size) return key
    val problem = when {
        der == null -> "is not valid base64"
        declared == null -> "decodes to ${der.size} bytes that are not a certificate"
        else -> "decodes to ${der.size} bytes, but its certificate header declares $declared, so it was cut short"
    }
    val fix = if (source == KEYCHAIN_SOURCE) {
        "Copy the key, then run: printf 'add-generic-password -U -a %s -s $name -w %s\\n' \"\$USER\" " +
            "\"\$(pbpaste)\" | security -i && pbcopy </dev/null"
    } else {
        "Fix or remove it there; it wins over the sources read after it."
    }
    throw GradleException("$name from $source has ${key.length} characters and $problem. $fix")
}

fun Provider<String>.javaString(): Provider<String> =
    map { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }

val networkPerFlavor = mapOf("dev" to "REGTEST", "mainnet" to "BITCOIN", "tnet" to "TESTNET")
val e2eEnv = envFlag("E2E", default = false)
val e2eBackendEnv = providers.environmentVariable("E2E_BACKEND").orElse("local")
val sparkLocalConfigEnv = providers.environmentVariable("SPARK_LOCAL_CONFIG_B64").orElse("")
val sparkLocalChainUrlEnv = providers.environmentVariable("SPARK_LOCAL_CHAIN_URL").orElse("")
val e2eLocalHostEnv = providers.environmentVariable("E2E_LOCAL_HOST").orElse("10.0.2.2")

extra["bitkitLocalProp"] = { key: String -> localProp(key) }
extra["bitkitEnvFlag"] = { key: String, default: Boolean -> envFlag(key, default) }

// BuildConfig fields by name, each a Java type and a Java literal, for the variant of [flavorName].
fun sparkBuildConfig(flavorName: String?): Map<String, Pair<String, Provider<String>>> = mapOf(
    "BREEZ_API_KEY" to ("String" to breezApiKey().javaString()),
    "ESPLORA_URL" to ("String" to localProp("ESPLORA_URL").orElse("").javaString()),
    "E2E" to ("boolean" to e2eEnv),
    "E2E_BACKEND" to ("String" to e2eBackendEnv.javaString()),
    "SPARK_LOCAL_CONFIG_B64" to ("String" to sparkLocalConfigEnv.javaString()),
    "SPARK_LOCAL_CHAIN_URL" to ("String" to sparkLocalChainUrlEnv.javaString()),
    "E2E_LOCAL_HOST" to ("String" to e2eLocalHostEnv.javaString()),
    "NETWORK" to ("String" to provider { networkOf(flavorName) }.javaString()),
)

fun networkOf(flavorName: String?): String =
    requireNotNull(networkPerFlavor[flavorName]) { "Unmapped network flavor '$flavorName'." }

extra["bitkitSparkBuildConfig"] = { flavorName: String? -> sparkBuildConfig(flavorName) }
