package to.bitkit.docs

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Keeps the paths `docs/features/` names in step with the repository: a moved or deleted file fails here until its
 * feature file is updated.
 */
class FeatureMapTest {
    private val projectRoot = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "docs/features/README.md").isFile }
    private val featureFiles = File(projectRoot, "docs/features").listFiles { file -> file.extension == "md" }.orEmpty()

    @Test
    fun `every repository path a feature file names exists`() {
        val missing = featureFiles.sortedBy { it.name }.flatMap { file ->
            REPO_PATH.findAll(file.readText())
                .map { it.groupValues[1].substringBefore(':').trimEnd('/') }
                .filterNot { '<' in it || '*' in it || '{' in it || "..." in it }
                .filterNot { File(projectRoot, it).exists() }
                .map { "${file.name}: $it" }
        }

        assertTrue(featureFiles.isNotEmpty(), "No feature files found under docs/features")
        assertTrue(
            missing.isEmpty(),
            "docs/features names paths that do not exist; update the feature file:\n${missing.joinToString("\n")}",
        )
    }

    private companion object {
        val REPO_PATH = Regex("""`((?:app|journeys|docs|scripts|\.github)/[^`\s]+)`""")
    }
}
