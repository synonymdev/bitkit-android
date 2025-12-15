# CLAUDE.md

This file provides guidance to AI agents like Cursor/Claude Code/Codex/WARP when working with code in this repository.

## Build Commands

```sh
# compile
./gradlew compileDevDebugKotlin

# Build for dev
./gradlew assembleDevDebug

# Run unit tests
./gradlew testDevDebugUnitTest

# Run specific unit test file
./gradlew testDevDebugUnitTest --tests LightningRepoTest

# Run instrumented tests
./gradlew connectedDevDebugAndroidTest

# Build for E2E tests
E2E=true ./gradlew assembleDevRelease

# Build for E2E tests with geoblocking disabled
GEO=false E2E=true ./gradlew assembleDevRelease

# Lint using detekt
./gradlew detekt

# Auto-format using detekt
./gradlew detekt --auto-correct

# Update detekt baseline
./gradlew detektBaseline

# Install dev build
./gradlew installDevDebug

# Clean build artifacts
./gradlew clean
```

## Architecture Overview

### Tech Stack
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose with Material3
- **Architecture**: MVVM with Hilt dependency injection
- **Database**: Room
- **Networking**: Ktor
- **Bitcoin/Lightning**: LDK Node, bitkitcore library
- **State Management**: StateFlow, SharedFlow
- **Navigation**: Compose Navigation with strongly typed routes
- **Push Notifications**: Firebase
- **Storage**: DataStore with json files

### Project Structure
- **app/src/main/java/to/bitkit/**
  - **App.kt**: Application class with Hilt setup
  - **ui/**: All UI components
    - **MainActivity.kt**: Single activity hosting all screens
    - **screens/**: Feature-specific screens organized by domain
    - **components/**: Reusable UI components
    - **theme/**: Material3 theme configuration
  - **viewmodels/**: Shared ViewModels for business logic
  - **repositories/**: Data access layer
  - **services/**: Core services (Lightning, Currency, etc.)
  - **data/**: Data layer: database, DTOs, and data stores
  - **di/**: Dependency Injection: Hilt modules
  - **models/**: Domain models
  - **ext/**: Kotlin extensions
  - **utils/**: Utility functions
  - **usecases/**: Domain layer: use cases

### Key Architecture Patterns
1. **Single Activity Architecture**: MainActivity hosts all screens via Compose Navigation
2. **Repository Pattern**: Repositories abstract data sources from ViewModels
3. **Service Layer**: Core business logic in services (LightningService, WalletService)
4. **Reactive State Management**: ViewModels expose UI state via StateFlow
5. **Coroutine-based Async**: All async operations use Kotlin coroutines

### Build Variants
- **dev**: Regtest network for development
- **tnet**: Testnet network
- **mainnet**: Production (currently commented out)

## Common Pitfalls

### ❌ DON'T
```kotlin
GlobalScope.launch { }                          // Use viewModelScope
val result = nullable!!.doSomething()           // Use safe calls
Text("Send Payment")                            // Use string resources
class Service(@Inject val vm: ViewModel)        // Never inject VMs
suspend fun getData() = runBlocking { }         // Use withContext
```

### ✅ DO
```kotlin
viewModelScope.launch { }
val result = nullable?.doSomething() ?: default
Text(stringResource(R.string.send_payment))
class Service { fun process(data: Data) }
suspend fun getData() = withContext(Dispatchers.IO) { }
```

## Key File Paths

- **Main Activity**: `app/src/main/java/to/bitkit/ui/MainActivity.kt`
- **Navigation**: `app/src/main/java/to/bitkit/ui/ContentView.kt`
- **Lightning Service**: `app/src/main/java/to/bitkit/services/LightningService.kt`
- **App ViewModel**: `app/src/main/java/to/bitkit/viewmodels/AppViewModel.kt`
- **Wallet ViewModel**: `app/src/main/java/to/bitkit/viewmodels/WalletViewModel.kt`

## Common Patterns

### ViewModel State
```kotlin
private val _uiState = MutableStateFlow(InitialState)
val uiState: StateFlow<UiState> = _uiState.asStateFlow()

fun updateState(action: Action) {
    viewModelScope.launch {
        _uiState.update { it.copy(/* fields */) }
    }
}
```

### Repository
```kotlin
suspend fun getData(): Result<Data> = withContext(Dispatchers.IO) {
    runCatching {
        Result.success(apiService.fetchData())
    }.onFailure { e ->
        Logger.error("Failed", e = e, context = TAG)
    }
}
```

### Rules
- USE coding rules from `.cursor/default.rules.mdc`
- ALWAYS run `./gradlew compileDevDebugKotlin` after code changes to verify code compiles
- ALWAYS run `./gradlew testDevDebugUnitTest` after code changes to verify tests succeed and fix accordingly
- ALWAYS run `./gradlew detekt` after code changes to check for new lint issues and fix accordingly
- ALWAYS ask clarifying questions to ensure an optimal plan when encountering functional or technical uncertainties in requests
- ALWAYS when fixing lint or test failures prefer to do the minimal amount of changes to fix the issues
- USE single-line commit messages under 50 chars; use template format: `feat: add something new`
- USE `git diff HEAD sourceFilePath` to diff an uncommitted file against the last commit
- ALWAYS check existing code patterns before implementing new features
- USE existing extensions and utilities rather than creating new ones
- ALWAYS consider applying YAGNI (You Aren't Gonna Need It) principle for new code 
- ALWAYS reuse existing constants
- ALWAYS ensure a method exist before calling it
- ALWAYS remove unused code after refactors
- ALWAYS follow Material3 design guidelines for UI components
- ALWAYS ensure proper error handling in coroutines
- ALWAYS acknowledge datastore async operations run synchronously in a suspend context
- NEVER use `runBlocking` in suspend functions
- ALWAYS pass the TAG as context to `Logger` calls, e.g. `Logger.debug("message", context = TAG)`
- ALWAYS use the Result API instead of try-catch
- NEVER wrap methods returning `Result<T>` in try-catch
- NEVER inject ViewModels as dependencies - Only android activities and composable functions can use viewmodels
- NEVER hardcode strings and always preserve string resources
- ALWAYS localize in ViewModels using injected `@ApplicationContext`, e.g. `context.getString()`
- ALWAYS use `remember` for expensive Compose computations
- ALWAYS add modifiers to the last place in the argument list when calling `@Composable` functions
- PREFER declaring small dependant classes, constants, interfaces or top-level functions in the same file with the core class where these are used
- ALWAYS create data classes for state AFTER viewModel class in same file
- ALWAYS return early where applicable, PREFER guard-like `if` conditions like `if (condition) return`
- ALWAYS write the documentation for new features as markdown files in `docs/`
- NEVER write code in the documentation files
- NEVER add code comments to private functions, classes, etc
- ALWAYS use `_uiState.update { }`, NEVER use `_stateFlow.value =`
- ALWAYS add the warranted changes in unit tests to keep the unit tests succeeding
- ALWAYS follow the patterns of the existing code in `app/src/test` when writing new unit tests
- ALWAYS be mindful of thread safety when working with mutable lists & state
- ALWAYS split screen composables into parent accepting viewmodel + inner private child accepting state and callbacks `Content()`
- ALWAYS name lambda parameters in a composable function using present tense, NEVER use past tense
- ALWAYS list 3 suggested commit messages after implementation work
- NEVER use `wheneverBlocking` when in an unit test where you're using expression body and already wrapping the test with a `= test {}` lambda.
- ALWAYS add business logic to Repository layer via methods returning `Result<T>` and use it in ViewModels
- ALWAYS use services to wrap RUST code exposed via bindings
- ALWAYS order upstream architectural data flow this way: `UI -> ViewModel -> Repository -> RUST` and vice-versa for downstream
- ALWAYS add new string string resources in alphabetical order in `strings.xml` 
- ALWAYS use template in `.github/pull_request_template.md` for PR descriptions
- ALWAYS wrap `ULong` numbers with `USat` in arithmetic operations, to guard against overflows

### Architecture Guidelines
- Use `LightningNodeService` to manage background notifications while the node is running
- Use `LightningService` to wrap node's RUST APIs and manage the inner lifecycle of the node
- Use `LightningRepo` to defining the business logic for the node operations, usually delegating to `LightningService`
- Use `WakeNodeWorker` to manage the handling of remote notifications received via cloud messages
