# Android Libraries Documentation Reference

This document provides a comprehensive reference for all libraries used in the bitkit-android project, including their documentation links and key usage patterns.

## Core Android Libraries

### Kotlin
- **Documentation**: https://kotlinlang.org/docs/
- **API Reference**: https://kotlinlang.org/api/latest/jvm/stdlib/

### Android Core
- **Core KTX**: https://developer.android.com/kotlin/ktx
- **AppCompat**: https://developer.android.com/jetpack/androidx/releases/appcompat
- **Material Design**: https://material.io/develop/android
- **Core Splashscreen**: https://developer.android.com/develop/ui/views/launch/splash-screen

## UI Framework

### Jetpack Compose
- **Main Documentation**: https://developer.android.com/jetpack/compose
- **BOM Mapping**: https://developer.android.com/develop/ui/compose/bom/bom-mapping
- **Material 3**: https://developer.android.com/jetpack/compose/designsystems/material3
- **Navigation**: https://developer.android.com/jetpack/compose/navigation
- **Tooling**: https://developer.android.com/jetpack/compose/tooling

### Accompanist
- **Documentation**: https://google.github.io/accompanist/
- **Permissions**: https://google.github.io/accompanist/permissions/
- **Pager Indicators**: https://google.github.io/accompanist/pager/

### Layout
- **ConstraintLayout Compose**: https://developer.android.com/jetpack/compose/layouts/constraintlayout

## Architecture & Dependency Injection

### Hilt
- **Documentation**: https://dagger.dev/hilt/
- **Android Guide**: https://developer.android.com/training/dependency-injection/hilt-android
- **Compose Integration**: https://developer.android.com/jetpack/compose/libraries#hilt

### Lifecycle Components
- **Documentation**: https://developer.android.com/jetpack/androidx/releases/lifecycle
- **ViewModel**: https://developer.android.com/topic/libraries/architecture/viewmodel
- **Compose Integration**: https://developer.android.com/jetpack/compose/libraries#viewmodel

### Data Persistence
- **DataStore Preferences**: https://developer.android.com/topic/libraries/architecture/datastore
- **Room**: https://developer.android.com/training/data-storage/room

## Networking & Serialization

### Ktor
- **Documentation**: https://ktor.io/docs/
- **Client Documentation**: https://ktor.io/docs/getting-started-ktor-client.html
- **Android Guide**: https://ktor.io/docs/client-engines.html#android

### Serialization
- **Kotlinx Serialization**: https://kotlinlang.org/docs/serialization.html
- **Protobuf**: https://protobuf.dev/

## Bitcoin & Lightning Network

### LDK Node Android
- **GitHub**: https://github.com/synonymdev/ldk-node (Fork)
- **Upstream Docs**: https://lightningdevkit.org/
- **API Reference**: https://docs.rs/ldk-node/latest/ldk_node/

### Bitkit Core
- **GitHub**: https://github.com/synonymdev/bitkit-core
- **Custom Android bindings for Bitcoin operations**

### Cryptography
- **BouncyCastle**: https://www.bouncycastle.org/java.html
- **Provider Documentation**: https://www.bouncycastle.org/documentation.html

## Media & Scanning

### Camera
- **CameraX**: https://developer.android.com/training/camerax
- **Camera2 API**: https://developer.android.com/training/camerax/architecture

### Barcode/QR Scanning
- **ZXing**: https://github.com/zxing/zxing
- **ML Kit Barcode**: https://developers.google.com/ml-kit/vision/barcode-scanning

### Animations
- **Lottie Compose**: https://airbnb.io/lottie/#/android-compose

## Firebase

### Firebase Platform
- **Documentation**: https://firebase.google.com/docs/android/setup
- **Messaging**: https://firebase.google.com/docs/cloud-messaging/android/client
- **Analytics**: https://firebase.google.com/docs/analytics/get-started?platform=android

## Security & Authentication

### Biometric
- **Documentation**: https://developer.android.com/jetpack/androidx/releases/biometric
- **Guide**: https://developer.android.com/training/sign-in/biometric-auth

## Background Processing

### WorkManager
- **Documentation**: https://developer.android.com/topic/libraries/architecture/workmanager
- **Hilt Integration**: https://developer.android.com/training/dependency-injection/hilt-jetpack

## Utilities

### Date/Time
- **Kotlinx DateTime**: https://github.com/Kotlin/kotlinx-datetime

### Charts
- **Compose Charts**: https://github.com/ehsannarmani/ComposeCharts

### Native Libraries
- **JNA**: https://github.com/java-native-access/jna

## Testing Libraries

### Unit Testing
- **JUnit**: https://junit.org/junit4/
- **Mockito Kotlin**: https://github.com/mockito/mockito-kotlin
- **Robolectric**: http://robolectric.org/
- **Turbine**: https://github.com/cashapp/turbine

### Android Testing
- **Espresso**: https://developer.android.com/training/testing/espresso
- **Compose Testing**: https://developer.android.com/jetpack/compose/testing
- **Hilt Testing**: https://developer.android.com/training/dependency-injection/hilt-testing

## Key Configuration Notes

### Compose Compiler Flags
- StrongSkipping is disabled
- OptimizeNonSkippingGroups is enabled

### Build Configuration
- Minimum SDK: 28
- Target SDK: 35
- Kotlin JVM Target: 11
- Compose BOM manages all Compose library versions

## Development Guidelines

### When Adding New Libraries
1. Check if functionality exists in current libraries first
2. Prefer AndroidX/Jetpack libraries when available
3. Ensure compatibility with current Compose BOM version
4. Add to libs.versions.toml for version management
5. Update this documentation with links and usage notes

### Version Management
- All versions are centralized in `gradle/libs.versions.toml`
- Use BOM (Bill of Materials) for related library groups
- Keep major dependencies (Compose, Kotlin, Hilt) aligned

### Testing Strategy
- Unit tests: JUnit + Mockito + Robolectric
- Integration tests: Hilt testing + Room testing
- UI tests: Compose testing + Espresso
- Flow testing: Turbine for StateFlow/Flow testing 
