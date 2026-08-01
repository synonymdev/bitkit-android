import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuildConfigField
import com.android.build.api.variant.BuiltArtifactsLoader
import com.android.build.api.variant.FilterConfiguration
import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.stability.analyzer)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.google.services)
    alias(libs.plugins.room)
    alias(libs.plugins.detekt)
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

val localProperties by lazy {
    Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { load(it) }
        }
    }
}

fun localProp(key: String): Provider<String> {
    return providers.environmentVariable(key)
        .orElse(providers.gradleProperty(key))
        .orElse(provider { localProperties.getProperty(key) })
}

fun envFlag(key: String, default: Boolean): Provider<String> {
    return providers.environmentVariable(key)
        .map { it.toBoolean().toString() }
        .orElse(default.toString())
}

// Android resource qualifier format for androidResources.localeFilters
val androidLocales = listOf(
    "en", "ar", "b+es+419", "ca", "cs", "de", "el", "es", "es-rES", "fr", "it", "nl", "pl", "pt", "pt-rBR", "ru"
)
// BCP 47 format for BuildConfig.LOCALES (used with Locale.forLanguageTag())
val bcp47Locales = listOf(
    "en", "ar", "es-419", "ca", "cs", "de", "el", "es", "es-ES", "fr", "it", "nl", "pl", "pt", "pt-BR", "ru"
)
val e2eEnv = envFlag("E2E", default = false)
val e2eBackendEnv = providers.environmentVariable("E2E_BACKEND").orElse("local")
val e2eHomegateUrlEnv = providers.environmentVariable("E2E_HOMEGATE_URL").orElse("http://127.0.0.1:6288")
val geoEnv = envFlag("GEO", default = true)
val paykitUiDisabledEnv = envFlag("PAYKIT_UI_DISABLED", default = false)
val trezorBridgeEnv = localProp("TREZOR_BRIDGE").map { it.toBoolean().toString() }.orElse("false")
val trezorBridgeUrlEnv = localProp("TREZOR_BRIDGE_URL").orElse("http://10.0.2.2:21325")
val networkPerFlavor = mapOf("dev" to "REGTEST", "mainnet" to "BITCOIN", "tnet" to "TESTNET")
val requestedNdkVersion = providers.environmentVariable("NDK_VERSION").orNull?.takeIf { it.isNotBlank() }
val androidTestAnnotationPackage = "to.bitkit.test.annotations"
val androidTestTaskPrefix = "connectedDevDebug"
val androidTestTaskSuffix = "AndroidTest"
val baseAndroidTestTaskName = "$androidTestTaskPrefix$androidTestTaskSuffix"
val androidTestAnnotationNames = file("src/androidTest/java/to/bitkit/test/annotations")
    .listFiles()
    ?.mapNotNull { file ->
        file.nameWithoutExtension.takeIf {
            file.isFile &&
                file.extension == "kt"
        }
    }
    ?.sorted()
    .orEmpty()
val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(":") }

fun androidTestTaskName(annotationName: String): String {
    return "$androidTestTaskPrefix$annotationName$androidTestTaskSuffix"
}

fun isTaskNameAbbreviation(taskName: String, fullTaskName: String): Boolean {
    if (taskName.isEmpty() || taskName == fullTaskName) return false
    return fullTaskName.startsWith(taskName) ||
        taskName.any { it.isUpperCase() } &&
        taskName.isSubsequenceOf(fullTaskName)
}

fun String.isSubsequenceOf(value: String): Boolean {
    var searchIndex = 0
    for (char in this) {
        searchIndex = value.indexOf(char, startIndex = searchIndex)
        if (searchIndex == -1) return false
        searchIndex++
    }
    return true
}

val androidTestTaskNames = androidTestAnnotationNames.map { androidTestTaskName(it) }
val requestedBaseAndroidTestTaskNames = requestedTaskNames.filter { taskName ->
    taskName == baseAndroidTestTaskName ||
        isTaskNameAbbreviation(taskName, baseAndroidTestTaskName)
}
val abbreviatedAndroidTestTaskNames = requestedTaskNames.filter { taskName ->
    taskName !in requestedBaseAndroidTestTaskNames &&
        taskName !in androidTestTaskNames &&
        androidTestTaskNames.any { isTaskNameAbbreviation(taskName, it) }
}
require(abbreviatedAndroidTestTaskNames.isEmpty()) {
    "Use full generated Android test lane task names. Abbreviated lane tasks are unsupported: " +
        abbreviatedAndroidTestTaskNames.joinToString(", ")
}
val requestedAndroidTestAnnotationTaskNames = requestedTaskNames.filter { taskName ->
    taskName in androidTestTaskNames
}
require(requestedBaseAndroidTestTaskNames.isEmpty() || requestedAndroidTestAnnotationTaskNames.isEmpty()) {
    "Do not combine '$baseAndroidTestTaskName' with generated Android test lane tasks. Requested lanes: " +
        requestedAndroidTestAnnotationTaskNames.joinToString(", ")
}
require(requestedAndroidTestAnnotationTaskNames.size <= 1) {
    "Run only one generated Android test lane per Gradle invocation. Requested lanes: " +
        requestedAndroidTestAnnotationTaskNames.joinToString(", ")
}
val requestedAndroidTestAnnotationTask = requestedAndroidTestAnnotationTaskNames.singleOrNull()
val requestedAndroidTestAnnotationTaskName = requestedAndroidTestAnnotationTask?.let { taskName ->
    androidTestAnnotationNames.first { androidTestTaskName(it) == taskName }
}
val requestedAndroidTestAnnotation = providers.gradleProperty("bitkitAndroidTestAnnotation")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.also {
        require('.' !in it) {
            "Use a simple Android test annotation name, e.g. 'ComposeUi'."
        }
        require(it in androidTestAnnotationNames) {
            "Unsupported bitkitAndroidTestAnnotation '$it'. Supported annotations: " +
                androidTestAnnotationNames.joinToString(", ")
        }
    }
requestedAndroidTestAnnotationTaskName?.let { annotationName ->
    requestedAndroidTestAnnotation?.let {
        require(it == annotationName) {
            "Do not combine bitkitAndroidTestAnnotation '$it' with generated lane '$annotationName'."
        }
    }
}
val bitkitAndroidTestAnnotationName = requestedAndroidTestAnnotation
    ?: requestedAndroidTestAnnotationTaskName
val bitkitAndroidTestAnnotation = bitkitAndroidTestAnnotationName?.let {
    "$androidTestAnnotationPackage.$it"
}

android {
    namespace = "to.bitkit"
    compileSdk = 36
    requestedNdkVersion?.let { ndkVersion = it }
    defaultConfig {
        applicationId = "to.bitkit"
        minSdk = 28
        targetSdk = 36
        versionCode = 187
        versionName = "2.4.0"
        testInstrumentationRunner = "to.bitkit.test.HiltTestRunner"
        bitkitAndroidTestAnnotation?.let {
            testInstrumentationRunnerArguments["annotation"] = it
        }
        vectorDrawables {
            useSupportLibrary = true
        }
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
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val keystoreFile = keystoreProperties.getProperty("storeFile").takeIf { it.isNotEmpty() }
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
                // noinspection ChromeOsAbiSupport
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
            }
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    androidResources {
        @Suppress("UnstableApiUsage")
        localeFilters.addAll(androidLocales)
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
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
            // Only architectures supported by native libs (ldk-node, bitkit-core)
            // x86 not supported; x86_64 only for debug/emulator
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true // mockito
            isIncludeAndroidResources = true // robolectric
        }
    }
    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        freeCompilerArgs.addAll(
            listOf(
                "-XXLanguage:+PropertyParamAnnotationDefaultTargetMode",
            )
        )
    }
}

androidComponents {
    onVariants { variant ->
        val buildConfigFields = requireNotNull(variant.buildConfigFields) {
            "buildFeatures.buildConfig must stay enabled for '${variant.name}'."
        }
        buildConfigFields.put("E2E", e2eEnv.booleanField())
        buildConfigFields.put("E2E_BACKEND", e2eBackendEnv.stringField())
        buildConfigFields.put("E2E_HOMEGATE_URL", e2eHomegateUrlEnv.stringField())
        buildConfigFields.put("TREZOR_BRIDGE", trezorBridgeEnv.booleanField())
        buildConfigFields.put("TREZOR_BRIDGE_URL", trezorBridgeUrlEnv.stringField())
        buildConfigFields.put("GEO", geoEnv.booleanField())
        buildConfigFields.put("FEATURE_PAYKIT_UI_DISABLED", paykitUiDisabledEnv.booleanField())
        buildConfigFields.put("LOCALES", provider { bcp47Locales.joinToString(",") }.stringField())
        buildConfigFields.put(
            "NETWORK",
            provider {
                requireNotNull(networkPerFlavor[variant.flavorName]) {
                    "Unmapped network flavor '${variant.flavorName}'."
                }
            }.stringField(),
        )

        val variantSuffix = variant.name.replaceFirstChar { it.uppercase() }
        val outputDir = layout.buildDirectory.dir("outputs/bitkit/${variant.name}")
        val mainOutput = variant.outputs.firstOrNull { it.filters.isEmpty() } ?: variant.outputs.first()

        val collectApks = tasks.register<CollectBitkitApks>("collectBitkitApks$variantSuffix") {
            group = "build"
            description = "Publishes deterministically named copies of the ${variant.name} APKs."
            outputDirectory.set(outputDir)
            flavorName.set(variant.flavorName.orEmpty())
            buildTypeName.set(variant.buildType.orEmpty())
            fallbackVersionCode.set(mainOutput.versionCode)
            builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
        }
        variant.artifacts.use(collectApks)
            .wiredWith { it.apkDirectory }
            .toListenTo(SingleArtifact.APK)

        val collectBundle = tasks.register<CollectBitkitBundle>("collectBitkitBundle$variantSuffix") {
            group = "build"
            description = "Publishes a deterministically named copy of the ${variant.name} AAB."
            flavorName.set(variant.flavorName.orEmpty())
            buildTypeName.set(variant.buildType.orEmpty())
            versionCode.set(mainOutput.versionCode)
            outputFile.set(
                outputDir.zip(mainOutput.versionCode) { dir, code ->
                    dir.file("bitkit-${variant.flavorName.orEmpty()}-${variant.buildType.orEmpty()}-$code.aab")
                }
            )
        }
        variant.artifacts.use(collectBundle)
            .wiredWith { it.bundleFile }
            .toListenTo(SingleArtifact.BUNDLE)
    }
}

fun Provider<String>.stringField(): Provider<BuildConfigField<String>> =
    map { BuildConfigField(type = "String", value = "\"$it\"", comment = null) }

fun Provider<String>.booleanField(): Provider<BuildConfigField<String>> =
    map { BuildConfigField(type = "boolean", value = it, comment = null) }

@DisableCachingByDefault(because = "Copies existing artifacts, cheaper to redo than to cache")
abstract class CollectBitkitApks : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val flavorName: Property<String>

    @get:Input
    abstract val buildTypeName: Property<String>

    @get:Input
    abstract val fallbackVersionCode: Property<Int>

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @TaskAction
    fun collect() {
        val target = outputDirectory.get().asFile
        target.listFiles { file -> file.extension == "apk" }?.forEach { it.delete() }
        target.mkdirs()

        val builtArtifacts = requireNotNull(builtArtifactsLoader.get().load(apkDirectory.get())) {
            "Unable to load APK metadata from '${apkDirectory.get().asFile}'."
        }

        builtArtifacts.elements.forEach { artifact ->
            val abi = artifact.filters
                .firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
                ?: "universal"
            val versionCode = artifact.versionCode ?: fallbackVersionCode.get()
            val name = "bitkit-${flavorName.get()}-${buildTypeName.get()}-$versionCode-$abi.apk"

            Files.copy(
                File(artifact.outputFile).toPath(),
                target.resolve(name).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}

@DisableCachingByDefault(because = "Copies an existing artifact, cheaper to redo than to cache")
abstract class CollectBitkitBundle : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bundleFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Input
    abstract val flavorName: Property<String>

    @get:Input
    abstract val buildTypeName: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @TaskAction
    fun collect() {
        val target = outputFile.get().asFile
        target.parentFile
            .listFiles { file -> file.extension == "aab" }
            ?.forEach { Files.delete(it.toPath()) }
        target.parentFile.mkdirs()

        Files.copy(
            bundleFile.get().asFile.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
}

val nativeDebugSymbols by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

fun Provider<MinimalExternalModuleDependency>.nativeDebugSymbolsArtifact(): String {
    val dependency = get()
    val version = dependency.versionConstraint.requiredVersion
        .ifBlank { dependency.versionConstraint.preferredVersion }

    require(version.isNotBlank()) {
        "Native debug symbols dependency '${dependency.module}' must declare an explicit version."
    }

    return "${dependency.module.group}:${dependency.module.name}:$version:native-debug-symbols@zip"
}

val syncNativeDebugSymbolArtifacts by tasks.registering(Sync::class) {
    group = "build"
    description = "Downloads native debug symbol archives for release native dependencies."

    from(nativeDebugSymbols)
    into(layout.buildDirectory.dir("intermediates/native-debug-symbol-artifacts"))
}

dependencies {
    implementation(fileTree("libs") { include("*.aar", "*.jar") })
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(platform(libs.kotlin.bom))
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.appcompat)
    implementation(libs.activity.compose)
    implementation(libs.material)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.biometric)
    implementation(libs.zxing)
    implementation(libs.barcode.scanning)
    // CameraX
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    // Crypto
    implementation(libs.bouncycastle.provider.jdk)
    implementation(libs.ldk.node.android) { exclude(group = "net.java.dev.jna", module = "jna") }
    implementation(libs.bitkit.core)
    implementation(libs.paykit)
    implementation(libs.vss.client)
    nativeDebugSymbols(libs.bitkit.core.nativeDebugSymbolsArtifact())
    nativeDebugSymbols(libs.ldk.node.android.nativeDebugSymbolsArtifact())
    nativeDebugSymbols(libs.paykit.nativeDebugSymbolsArtifact())
    nativeDebugSymbols(libs.vss.client.nativeDebugSymbolsArtifact())
    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
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
    implementation(libs.compose.runtime.tracing)
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
    implementation(libs.haze)
    implementation(libs.haze.materials)
    // Image Loading
    implementation(platform(libs.coil.bom))
    implementation(libs.coil.compose)
    // Compose Navigation
    implementation(libs.navigation.compose)
    androidTestImplementation(libs.navigation.testing)
    implementation(libs.hilt.navigation.compose)
    // Hilt - DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    ksp(libs.hilt.compiler)
    androidTestImplementation(libs.hilt.android.testing) // instrumented tests
    kspAndroidTest(libs.hilt.android.compiler)
    testImplementation(libs.hilt.android.testing) // robolectric tests
    kspTest(libs.hilt.android.compiler)
    // WorkManager
    implementation(libs.hilt.work)
    implementation(libs.work.runtime.ktx)
    // Glance - AppWidgets
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    // Ktor - Networking
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    // Logging
    runtimeOnly(libs.slf4j.simple)
    implementation(libs.slf4j.api)
    // Room - DB
    implementation(libs.room.ktx)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    testImplementation(libs.room.testing)
    // Test + Debug
    androidTestImplementation(libs.test.kotlin)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.test.coroutines)
    androidTestImplementation(libs.test.espresso.core)
    androidTestImplementation(libs.test.junit.ext)
    testImplementation(libs.test.kotlin)
    testImplementation(libs.test.core)
    testImplementation(libs.test.coroutines)
    testImplementation(libs.test.junit)
    testImplementation(libs.test.junit.ext)
    testImplementation(libs.test.mockito.kotlin)
    testImplementation(libs.test.robolectric)
    testImplementation(libs.test.turbine)
    detektPlugins(libs.detekt.formatting)
    detektPlugins(libs.detekt.compose.rules)
}
// https://developer.android.com/jetpack/androidx/releases/room#gradle-plugin
room {
    schemaDirectory("$projectDir/schemas")
}

// region Tasks

tasks.withType<Detekt>().configureEach {
    ignoreFailures = true
    reports {
        html.required.set(true)
        sarif.required.set(true)
        md.required.set(false)
        txt.required.set(false)
        xml.required.set(false)
    }
}

tasks.withType<Test> {
    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED,
            TestLogEvent.STANDARD_OUT,
            TestLogEvent.STANDARD_ERROR,
        )

        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

// JDK 21+ prints warnings when ByteBuddy loads a dynamic Java agent during tests.
// Our test stack triggers this automatically.
// Explicitly enabling dynamic agent loading silences the warning without altering behavior.
tasks.withType<Test>().configureEach {
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}

androidTestAnnotationNames.forEach { annotationName ->
    tasks.register(androidTestTaskName(annotationName)) {
        group = "verification"
        description = "Runs devDebug Android tests annotated with '$annotationName'."
        dependsOn("connectedDevDebugAndroidTest")
    }
}

// endregion
