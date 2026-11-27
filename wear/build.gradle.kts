import com.android.build.api.variant.BuildConfigField
import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.detekt)
}

// Same signing as :app, which the Wear Data Layer and Play require of a watch app sharing the phone app's id.
val keystoreProperties by lazy {
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()

    if (keystorePropertiesFile.exists()) {
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    } else {
        keystoreProperties["storeFile"] = System.getenv("KEYSTORE_FILE") ?: ""
        keystoreProperties["storePassword"] = System.getenv("KEYSTORE_PASSWORD") ?: ""
        keystoreProperties["keyAlias"] = System.getenv("KEY_ALIAS") ?: ""
        keystoreProperties["keyPassword"] = System.getenv("KEY_PASSWORD") ?: ""
    }

    keystoreProperties
}

apply(from = rootProject.file("gradle/bitkit-env.gradle.kts"))

@Suppress("UNCHECKED_CAST")
val sparkBuildConfig = extra["bitkitSparkBuildConfig"] as (String?) -> Map<String, Pair<String, Provider<String>>>

val requestedNdkVersion = providers.environmentVariable("NDK_VERSION").orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "to.bitkit.wear"
    compileSdk = 37
    requestedNdkVersion?.let { ndkVersion = it }
    defaultConfig {
        applicationId = "to.bitkit"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "network"
    productFlavors {
        create("dev") {
            dimension = "network"
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "Bitkit Regtest")
            manifestPlaceholders["app_icon"] = "@mipmap/ic_launcher_regtest"
            manifestPlaceholders["app_icon_round"] = "@mipmap/ic_launcher_regtest_round"
        }
        create("mainnet") {
            dimension = "network"
            applicationIdSuffix = ""
            resValue("string", "app_name", "Bitkit")
            manifestPlaceholders["app_icon"] = "@mipmap/ic_launcher_orange"
            manifestPlaceholders["app_icon_round"] = "@mipmap/ic_launcher_orange_round"
        }
        create("tnet") {
            dimension = "network"
            applicationIdSuffix = ".tnet"
            resValue("string", "app_name", "Bitkit Testnet")
            manifestPlaceholders["app_icon"] = "@mipmap/ic_launcher_testnet"
            manifestPlaceholders["app_icon_round"] = "@mipmap/ic_launcher_testnet_round"
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val keystoreFile = keystoreProperties.getProperty("storeFile").takeIf { it.isNotEmpty() }
                ?.let { rootProject.file(it) }
            storeFile = if (keystoreFile?.exists() == true) keystoreFile else null
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            ndk {
                // noinspection ChromeOsAbiSupport
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            ndk {
                debugSymbolLevel = "FULL"
                // noinspection ChromeOsAbiSupport
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
        resValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            // x86_64 only for the emulator
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true // mockito
            all { it.maxHeapSize = "1g" }
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        disable += setOf(
            // Version bumps are handled by dependency updates, translations by the localization flow.
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
            "MissingTranslation",
            // targetSdk bumps are planned work; this check would fail CI on every new SDK release.
            "OldTargetApi",
            // Copy style is set by the designs.
            "TypographyEllipsis",
            "TypographyDashes",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        allWarningsAsErrors = true
        freeCompilerArgs.addAll(
            listOf(
                "-Xannotation-default-target=param-property",
                // Library deprecations stay visible without breaking the build.
                "-Xwarning-level=DEPRECATION:warning",
            )
        )
    }
}

androidComponents {
    onVariants { variant ->
        val buildConfigFields = requireNotNull(variant.buildConfigFields) {
            "buildFeatures.buildConfig must stay enabled for '${variant.name}'."
        }
        sparkBuildConfig(variant.flavorName).forEach { (name, field) ->
            buildConfigFields.put(name, field.second.map { BuildConfigField(type = field.first, value = it, comment = null) })
        }
    }
}

dependencies {
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(platform(libs.kotlin.bom))
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.activity.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.bitkit.core)
    implementation(libs.breez.sdk.spark) { exclude(group = "net.java.dev.jna", module = "jna") }
    // Lifecycle
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    // Wear
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear.compose.ui.tooling)
    // Hilt - DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    // Ktor - Networking
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    // Test
    testImplementation(libs.test.kotlin)
    testImplementation(libs.test.coroutines)
    testImplementation(libs.test.junit)
    testImplementation(libs.test.mockito.kotlin)
    testImplementation(libs.test.turbine)
    detektPlugins(libs.detekt.formatting)
    detektPlugins(libs.detekt.compose.rules)
}

tasks.withType<Detekt>().configureEach {
    reports {
        html.required.set(true)
        sarif.required.set(true)
        md.required.set(false)
        txt.required.set(false)
        xml.required.set(false)
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
    // JDK 21+ prints warnings when ByteBuddy loads a dynamic Java agent during tests.
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}
