// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    val kotlinVersion = "1.9.24"
    val hiltVersion = "2.51.1"
    id("com.android.application") version "8.5.1" apply false
    id("org.jetbrains.kotlin.android") version kotlinVersion apply false
    // id("org.jetbrains.kotlin.jvm") version kotlinVersion apply false // for room db
    id("org.jetbrains.kotlin.plugin.serialization") version kotlinVersion apply false
    id("com.google.gms.google-services") version "4.4.2" apply false

    // https://github.com/google/ksp/releases
    id("com.google.devtools.ksp") version "$kotlinVersion-1.0.20" apply false

    // https://github.com/google/dagger/releases/
    id("com.google.dagger.hilt.android") version hiltVersion apply false
}
