plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.google.services)
    alias(libs.plugins.room)
}
android {
    namespace = "to.bitkit"
    compileSdk = 34
    ndkVersion = "26.1.10909125" // probably required by LDK bindings? - safer to keep it for now.
    defaultConfig {
        applicationId = "to.bitkit"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".dev"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    composeOptions {
        // https://developer.android.com/jetpack/androidx/releases/compose-kotlin#pre-release_kotlin_compatibility
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    @Suppress("UnstableApiUsage")
    testOptions {
        unitTests {
            // isReturnDefaultValues = true     // mockito
            // isIncludeAndroidResources = true // robolectric
        }
    }
}
dependencies {
    implementation(fileTree("libs") { include("*.aar") })
    implementation(platform(libs.kotlin.bom))
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.activity.compose)
    implementation(libs.material)
    implementation(libs.datastore.preferences)
    // BDK + LDK
    implementation(libs.bdk.android)
    implementation(libs.ldk.node.android)
    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.analytics)
    // Lifecycle
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.compose) // ViewModel utils for Compose
    implementation(libs.lifecycle.livedata.ktx) // LiveData
    implementation(libs.lifecycle.runtime.ktx) // Lifecycles wo ViewModel/LiveData
    implementation(libs.lifecycle.runtime.compose) // Lifecycle utils for Compose
    implementation(libs.lifecycle.viewmodel.savedstate) // Saved state for ViewModel
    // Compose
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))
    implementation(libs.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.ui.tooling.preview)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
    androidTestImplementation(libs.ui.test.junit4)
    // Compose Navigation
    implementation(libs.navigation.compose)
    androidTestImplementation(libs.navigation.testing)
    implementation(libs.hilt.navigation.compose)
    // Hilt - DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    ksp(libs.hilt.compiler)
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
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(kotlin("test"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.junit)
    // testImplementation("androidx.test:core:1.6.1")
    // testImplementation("org.mockito:mockito-core:5.12.0")
    // testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    // testImplementation("org.robolectric:robolectric:4.13")
    // Other
    implementation(libs.guava) // for ByteArray.toHex()+
}
ksp {
    // cool but strict: https://developer.android.com/jetpack/androidx/releases/room#2.6.0
    // arg("room.generateKotlin", "true")
}
// https://developer.android.com/jetpack/androidx/releases/room#gradle-plugin
room {
    schemaDirectory("$projectDir/schemas")
}
