import org.jetbrains.kotlin.compose.compiler.gradle.ComposeFeatureFlag
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.google.services)
    alias(libs.plugins.room)
}

// https://developer.android.com/studio/publish/app-signing#secure-key
// Init keystoreProperties variable from keystore.properties file
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

android {
    namespace = "to.bitkit"
    compileSdk = 35
    defaultConfig {
        applicationId = "to.bitkit.dev"
        minSdk = 28
        targetSdk = 35
        versionCode = 5
        versionName = "0.0.5"
        testInstrumentationRunner = "to.bitkit.test.HiltTestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val keystoreFile = keystoreProperties.getProperty("storeFile").takeIf { it.isNotBlank() }
                ?.let { rootProject.file(it) }
            storeFile = if (keystoreFile?.exists() == true) keystoreFile else null
            // storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            ndk {
                //noinspection ChromeOsAbiSupport
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
            }
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            ndk {
                //noinspection ChromeOsAbiSupport
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    androidResources {
        @Suppress("UnstableApiUsage")
        localeFilters.addAll(listOf("en", "ar", "ca", "cs", "de", "el", "es", "fr", "it", "nl", "pl", "pt", "ru"))
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true     // mockito
            isIncludeAndroidResources = true // robolectric
        }
    }
    lint {
        abortOnError = false
    }
    applicationVariants.all {
        val variant = this
        outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                val apkName = "bitkit-android-${defaultConfig.versionCode}-${variant.name}.apk"
                output.outputFileName = apkName
            }
    }
}
composeCompiler {
    featureFlags = setOf(
        ComposeFeatureFlag.StrongSkipping.disabled(),
        ComposeFeatureFlag.OptimizeNonSkippingGroups,
    )
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
}
dependencies {
    implementation(fileTree("libs") { include("*.aar") })
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(platform(libs.kotlin.bom))
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.appcompat)
    implementation(libs.activity.compose)
    implementation(libs.material)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.datetime)
    implementation(libs.biometric)
    implementation(libs.zxing)
    implementation(libs.barcode.scanning)
    // CameraX
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    // Crypto
    implementation(libs.bouncycastle.provider.jdk)
    implementation(libs.ldk.node.android)
    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.analytics)
    // Lifecycle
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.compose) // ViewModel utils for Compose
    implementation(libs.lifecycle.process) // ProcessLifecycleOwner
    implementation(libs.lifecycle.runtime.ktx) // Lifecycles wo ViewModel/LiveData
    implementation(libs.lifecycle.runtime.compose) // Lifecycle utils for Compose
    implementation(libs.lifecycle.viewmodel.savedstate) // Saved state for ViewModel
    // Compose
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.compose.ui.test.junit4)
    implementation(libs.accompanist.pager.indicators)
    implementation(libs.accompanist.permissions)
    implementation(libs.constraintlayout.compose)

    implementation(libs.lottie)
    implementation(libs.charts)

    // Compose Navigation
    implementation(libs.navigation.compose)
    androidTestImplementation(libs.navigation.testing)
    implementation(libs.hilt.navigation.compose)
    // Hilt - DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    ksp(libs.hilt.compiler)
    androidTestImplementation(libs.hilt.android.testing) // instrumented tests
    testImplementation(libs.hilt.android.testing) // robolectric tests
    // WorkManager
    implementation(libs.hilt.work)
    implementation(libs.work.runtime.ktx)
    // Ktor - Networking
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    debugImplementation(libs.slf4j.simple)
    // Room - DB
    implementation(libs.room.ktx)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    testImplementation(libs.room.testing)
    // Test + Debug
    androidTestImplementation(kotlin("test"))
    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.test.coroutines)
    androidTestImplementation(libs.test.espresso.core)
    androidTestImplementation(libs.test.junit.ext)
    testImplementation(kotlin("test"))
    testImplementation(libs.test.core)
    testImplementation(libs.test.coroutines)
    testImplementation(libs.test.junit)
    testImplementation(libs.test.junit.ext)
    testImplementation(libs.test.mockito.kotlin)
    testImplementation(libs.test.robolectric)
    testImplementation(libs.test.turbine)
}
ksp {
    // cool but strict: https://developer.android.com/jetpack/androidx/releases/room#2.6.0
    // arg("room.generateKotlin", "true")
}
// https://developer.android.com/jetpack/androidx/releases/room#gradle-plugin
room {
    schemaDirectory("$projectDir/schemas")
}
tasks.withType<Test> {
    testLogging {
        events("passed", "skipped", "failed")
        // showStandardStreams = true
    }
}
