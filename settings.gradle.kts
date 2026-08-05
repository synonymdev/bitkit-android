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
        mavenLocal()
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
            url = uri("https://maven.pkg.github.com/pubky/paykit-rs")
            credentials {
                val (user, pass) = getGithubCredentials()
                username = user
                password = pass
            }
        }
        // Second's bark (Ark) bindings; public registry, no credentials required
        maven { url = uri("https://gitlab.com/api/v4/projects/78057981/packages/maven") }
    }
}
rootProject.name = "bitkit-android"
include(":app")
