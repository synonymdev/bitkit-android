package to.bitkit.build

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildOutputContractTest {

    private companion object {
        val PARALLELISM_PROPERTIES = listOf(
            "org.gradle.daemon=true",
            "org.gradle.parallel=true",
            "org.gradle.tooling.parallel=true",
            "org.gradle.caching=true",
            "org.gradle.configuration-cache=true",
        )
        val ARTIFACT_CONSUMERS = listOf(
            "Justfile",
            ".github/workflows/e2e.yml",
            ".github/workflows/e2e_migration.yml",
            ".github/workflows/release.yml",
            ".github/workflows/release-internal.yml",
            ".agents/commands/release.md",
        )
    }

    private val repoRoot = generateSequence(
        Path(requireNotNull(System.getProperty("user.dir")) { "user.dir is required" }),
    ) { it.parent }
        .first { it.resolve("gradle/libs.versions.toml").exists() }

    @Test
    fun `build logic uses no internal or legacy AGP variant APIs`() {
        val buildFile = repoRoot.resolve("app/build.gradle.kts").readText()

        assertFalse(
            buildFile.contains("com.android.build.gradle.internal"),
            "Build logic must not reference internal AGP APIs, they are removed in AGP 9.",
        )
        assertFalse(
            buildFile.contains("applicationVariants"),
            "Build logic must not use the legacy variant API, it is removed in AGP 9.",
        )
        assertFalse(
            buildFile.contains("org.jetbrains.kotlin.android"),
            "Built-in Kotlin replaces the kotlin-android plugin in AGP 9.",
        )
    }

    @Test
    fun `artifacts are collected through public AGP artifact APIs`() {
        val buildFile = repoRoot.resolve("app/build.gradle.kts").readText()

        assertTrue(
            buildFile.contains("toListenTo(SingleArtifact.APK)"),
            "APK copies must be collected by listening to the public APK artifact.",
        )
        assertTrue(
            buildFile.contains("toListenTo(SingleArtifact.BUNDLE)"),
            "AAB copies must be collected by listening to the public BUNDLE artifact.",
        )
        assertTrue(
            buildFile.contains("BuiltArtifactsLoader"),
            "APK metadata must be read through the public BuiltArtifactsLoader.",
        )
        assertTrue(
            buildFile.contains("""layout.buildDirectory.dir("outputs/bitkit/${'$'}{variant.name}")"""),
            "Collected artifacts must be published under outputs/bitkit/<variant>.",
        )
    }

    @Test
    fun `generated BuildConfig fields are declared per variant`() {
        val buildFile = repoRoot.resolve("app/build.gradle.kts").readText()
        val fields = listOf(
            "E2E",
            "E2E_BACKEND",
            "E2E_HOMEGATE_URL",
            "TREZOR_BRIDGE",
            "TREZOR_BRIDGE_URL",
            "GEO",
            "FEATURE_PAYKIT_UI_DISABLED",
            "LOCALES",
            "NETWORK",
        )

        assertFalse(
            buildFile.contains("buildConfigField("),
            "BuildConfig fields must come from androidComponents.onVariants, not the legacy DSL.",
        )
        fields.forEach { field ->
            assertTrue(
                Regex("""buildConfigFields\.put\(\s*"$field"""").containsMatchIn(buildFile),
                "BuildConfig field '$field' must be preserved on the variant API.",
            )
        }
    }

    @Test
    fun `parallelism properties are declared exactly once`() {
        val properties = repoRoot.resolve("gradle.properties").readText().lines().map { it.trim() }

        PARALLELISM_PROPERTIES.forEach { property ->
            assertEquals(
                1,
                properties.count { it == property },
                "'$property' must be declared exactly once.",
            )
        }
    }

    @Test
    fun `artifact consumers read the collected outputs`() {
        ARTIFACT_CONSUMERS.forEach { consumer ->
            val content = repoRoot.resolve(consumer).readText()

            assertTrue(
                content.contains("app/build/outputs/bitkit/"),
                "'$consumer' must consume the collected artifacts.",
            )
            assertFalse(
                content.contains("app/build/outputs/apk/mainnet/release"),
                "'$consumer' must not read release APKs from the AGP-native output directory.",
            )
            assertFalse(
                content.contains("app/build/outputs/bundle/mainnetRelease/bitkit"),
                "'$consumer' must not read renamed AABs from the AGP-native output directory.",
            )
        }
    }
}
