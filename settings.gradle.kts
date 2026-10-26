import java.util.*

val localProperties by lazy {
    Properties().apply {
        val file = rootDir.resolve("local.properties")
        if (file.exists()) {
            file.inputStream().use { load(it) }
        }
    }
}
fun getGithubCredentials(
    passKey: String = "gpr.key",
    userKey: String = "gpr.user",
): Pair<String?, String?> {
    val user = System.getenv("GPR_USER")
        ?: System.getenv("GITHUB_ACTOR")
        ?: providers.gradleProperty(userKey).orNull
        ?: localProperties.getProperty(userKey)
    val key = System.getenv("GPR_TOKEN")
        ?: System.getenv("GITHUB_TOKEN")
        ?: providers.gradleProperty(passKey).orNull
        ?: localProperties.getProperty(passKey)
    return user to key
}
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Opt in per library to test locally published builds; release coordinates otherwise
        // resolve from GitHub Packages, so native libraries match their published debug symbols.
        val localModules = mapOf(
            "ldk-node" to "ldk-node-android",
            "vss" to "vss-client-android",
            "bitkit-core" to "bitkit-core-android",
            "paykit" to "paykit-android",
        )
        val selectedLocalLibraries = when (val value = providers.gradleProperty("useMavenLocal").orNull?.trim()) {
            null, "", "false" -> emptyList()
            "true" -> listOf("ldk-node", "vss")
            else -> value.split(",").map { it.trim() }.distinct()
        }
        require(selectedLocalLibraries.all { it in localModules }) {
            "useMavenLocal must be true, false, or a comma-separated list of: ${localModules.keys.joinToString()}"
        }
        if (selectedLocalLibraries.isNotEmpty()) {
            mavenLocal {
                content {
                    selectedLocalLibraries.forEach {
                        includeModule("com.synonym", localModules.getValue(it))
                    }
                }
            }
        }
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/synonymdev/bitkit-core")
            credentials {
                val (user, pass) = getGithubCredentials()
                username = user
                password = pass
            }
        }
        maven {
            url = uri("https://maven.pkg.github.com/synonymdev/vss-rust-client-ffi")
            credentials {
                val (user, pass) = getGithubCredentials()
                username = user
                password = pass
            }
        }
        maven {
            url = uri("https://maven.pkg.github.com/synonymdev/ldk-node")
            credentials {
                val (user, pass) = getGithubCredentials()
                username = user
                password = pass
            }
        }
        maven {
            url = uri("https://mvn.breez.technology/releases")
            content { includeGroup("breez_sdk_spark") }
        }
        maven {
            url = uri("https://maven.pkg.github.com/pubky/paykit-rs")
            credentials {
                val (user, pass) = getGithubCredentials()
                username = user
                password = pass
            }
        }
    }
}
rootProject.name = "bitkit-android"
include(":app")
