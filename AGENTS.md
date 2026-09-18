# CLAUDE.md

This file provides guidance to Codex, Claude Code, and Cursor when working with code in this repository.

## Agent Commands

Durable shared agent command specs live in `.agents/commands/`. For PR creation, follow `.agents/commands/pr.md`; `.claude/commands` is a compatibility symlink to the same files.

## Build Commands

```sh
# compile
just compile

# Build, install, launch dev app on connected target
just run

# Build for dev
just build

# Run unit tests
just test

# Run specific unit test file
just test file LightningRepoTest

# Run instrumented tests
just test android

# Build for E2E tests (UI hooks enabled, local Electrum by default)
just e2e

# Build for E2E tests with geoblocking disabled
just e2e no geo

# Build for E2E tests using network Electrum (not local; staging/mainnet based on flavor)
just e2e network assembleTnetRelease

# Lint using detekt
just lint

# Auto-format using detekt
just format

# Update detekt baseline
just lint baseline

# Install dev build
just install

# Clean build artifacts
just clean
```

## Prerequisites

- The **`android` CLI and the `android-cli` skill** are required, not recommended: they are how an
  agent drives a connected emulator or device, so without them the journeys under `journeys/` cannot
  run and a PR's QA contract cannot be checked. Install the CLI as below.

### Agent CLI (android)

Agents drive a connected emulator or device with the `android` CLI, which wraps the SDK tooling
and adds a semantic UI dump. It is not provisioned by this repo — install it if it is missing, from
`https://dl.google.com/android/cli/latest/<platform>/install.sh` where `<platform>` is one of
`darwin_arm64`, `darwin_x86_64` or `linux_x86_64`:

```sh
curl -fsSL https://dl.google.com/android/cli/latest/darwin_arm64/install.sh | bash
```

On Windows the installer is a `.cmd` instead:
`curl -fsSL https://dl.google.com/android/cli/latest/windows_x86_64/install.cmd -o "%TEMP%\i.cmd" && "%TEMP%\i.cmd"`.

Discover arguments with `--help` rather than memorizing them.

```sh
# Emulators
android emulator list                  # AVD names; `start` requires one, it has no default
android emulator start Pixel_9

# Inspect the screen
android layout --pretty                # flat JSON of on-screen elements
android layout --diff                  # only what changed, to keep context small
android screen capture -o shot.png     # secondary; use when layout hits a WebView or animation
android screen capture --annotate -o shot.png   # numbered boxes, for elements layout cannot name

# Interact
adb shell input tap <x> <y>            # use an element's `center`
```

`android layout` reports each element's `resource-id`, `text`, `content-desc`, `interactions`,
`bounds` and `center`. Compose `testTag`s land in `resource-id`. **The JSON keys are hyphenated, not
camelCase** — the `android-cli` skill's `references/interact.md` documents `resourceId` and
`contentDesc`, and a filter written against those names matches nothing.

Prefer `android layout` over screenshots: it names elements by their test tag, and full-resolution
screenshots can exceed image size limits. It does not see everything, though — toasts never appear
in it, some tiles carry no text, and an element can be missing from one dump and present in the
next. `journeys/README.md` lists what needs a screenshot instead.

## Journeys

`journeys/` holds XML walkthroughs of app behaviour that an agent evaluates by driving a running
emulator or device — number pad caps, notification permission, widget flows, deeplinks, hardware
wallet pairing and transfers. Read [`journeys/README.md`](journeys/README.md) before running or
writing one; it has the format, the runner commands and the per-suite preconditions.

- Journeys are **the QA contract for a PR**. A PR with a user-visible change adds or updates the
  journeys that prove it and any journey whose route it changes, and lists them under `#### Journeys`
  in the PR body. Reviewers drive the listed journeys on a device; nothing in `.github/workflows`
  reads `journeys/`. Write a manual test only for a step that needs a capability the Capabilities
  table in [`journeys/README.md`](journeys/README.md) does not list.
- A journey is **not the source of truth** for app behaviour, despite what the `android-cli` skill's
  own `references/journeys.md` says. A journey that disagrees with the app is most likely stale. Say
  what you found and update the journey; escalate only once you have separately confirmed the app is
  wrong. A crash, exit or freeze is the exception — stop there and escalate.
- **PORT the journeys whenever a feature crosses to or from iOS.** If a change ships or touches a
  journey under `journeys/`, the matching `bitkit-ios` PR carries it, and vice versa. A ported
  feature without its journey is an incomplete port.
- KEEP the file name, `<journey name>` and `<action>` prose identical across the two repos so the
  specs stay diffable. Change only what the platform forces.
- MATCH the iOS identifier string when adding a `testTag` a journey asserts on — the vocabulary is
  deliberately shared (`N9`, `NRemove`, `SpendingAmountContinue`, `HardwareTransferSign`). Record any
  name that cannot match in the identifier table in `journeys/README.md`.
- ADAPT rather than transcribe when a platform genuinely behaves differently, and say so in the
  journey's `<description>` and the suite README — never assert behaviour the platform does not have.
- SKIP a journey only when the feature does not exist on the other side, and record it under the
  cross-platform table in `journeys/README.md` with what is missing.

### Running a journey on both platforms

A journey is a shared spec, so when a behaviour is meant to match iOS, run the same file on both
sides rather than reasoning about the difference. Android uses the `android` CLI above; iOS uses the
XcodeBuildMCP CLI against a `bitkit-ios` checkout:

```sh
xcodebuildmcp simulator build-and-run                 # build, install, launch, capture logs
xcodebuildmcp simulator snapshot-ui                   # the `android layout` equivalent
xcodebuildmcp ui-automation tap --element-ref e12     # tap one ref from the latest snapshot
xcodebuildmcp ui-automation wait-for-ui --identifier SpendingAmount --predicate exists
```

`snapshot-ui` names elements by `accessibilityIdentifier` the way `android layout` names them by
`resource-id`, so a journey's tag assertions map onto both. The vocabulary really is shared —
Settings on both platforms agrees on `Tab-general`, `Tab-security`, `Tab-advanced`, `NavigationBack`,
`HeaderMenu`, `CurrenciesSettings`, `UnitSettings`, `WidgetsSettings` and `QuickpaySettings` — so a
comparison run is mostly signal and the rows that disagree stand out.

A cross-platform Lightning payment is the sharpest single check that the two builds agree: take an
invoice from one side and pay it from the other (`xcrun simctl pbpaste <udid>` after tapping Copy on
iOS, then the `lightning:` URI route above on Android).

When the two platforms disagree on a journey, write down which it looks like — an intentional
platform difference, or something worth a closer look — in the journey's `<description>` and the
suite README, on both sides, so the next reader does not rediscover it. A disagreement is a prompt to
investigate, not a bug report on its own.

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
- **Storage**: DataStore with JSON files

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
- **mainnet**: Production

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
class Service {
  fun process(data: Data)
}

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
  runSuspendCatching {
    apiService.fetchData()
  }.onFailure {
    Logger.error("Failed", it, context = TAG)
  }
}
```

### Rules

- USE coding rules from `.cursor/default.rules.mdc`
- For multi-step changes, stacked PR surgery, and review follow-up with several small edits, batch validation instead of running the full build/check suite after every edit. Run the relevant Gradle checks once the coherent change set is ready, before updating a PR or pushing.
- Still run `just compile`, `just test`, and `just lint` before the final PR update/push for code changes, and fix failures before pushing.
- After fixing validation failures, rerun the narrowest useful check that proves the fix. If only test files changed, prefer the targeted test task and a test-focused lint/detekt check when the project tooling supports it; otherwise use the standard detekt task before pushing.
- Use narrower checks earlier only when they answer an immediate risk, e.g. a single unit test after touching focused business logic or a Kotlin compile after a risky refactor.
- ALWAYS ask clarifying questions to ensure an optimal plan when encountering functional or technical uncertainties in requests
- ALWAYS when fixing lint or test failures prefer to do the minimal amount of changes to fix the issues
- USE single-line commit messages under 72 chars; use conventional commit messages template format: `feat: add something new`
- USE `git diff HEAD sourceFilePath` to diff an uncommitted file against the last commit
- NEVER capitalize words in commit messages
- ALWAYS create a `*-backup` branch before performing a rebase
- ALWAYS suggest 3 commit messages with confidence score ratings, e.g. `fix: show toast on resolution (90%)`. In plan mode, include them at the end of the plan. If the user picks one via plan update, commit after implementation. Outside plan mode, suggest after implementation completes. In both cases, run `git status` to check ALL uncommitted changes after completing code edits
- ALWAYS check existing code patterns before implementing new features
- USE existing extensions and utilities rather than creating new ones
- ALWAYS use or create `Context` extension properties in `ext/Context.kt` instead of raw `context.getSystemService()` casts
- NEVER use `System.currentTimeMillis()`, use time helpers from `ext/DateTime.kt` instead (e.g. `nowMillis()`, `Clock.nowMs()`) — they accept a `Clock` and are unit-testable
- ALWAYS apply the YAGNI (You Ain't Gonna Need It) principle for new code
- ALWAYS reuse existing constants
- ALWAYS ensure a method exist before calling it
- ALWAYS remove unused code after refactors
- ALWAYS follow Material3 design guidelines for UI components
- When building from a Figma frame, reuse only scaffolding (sheet host, `SheetTopBar`, buttons, typography); NEVER swap a design-specific illustration/animation for a lookalike. Export the frame's assets via the Figma MCP and read animation timing/easing/direction from prototype reactions (`use_figma` → `node.reactions`)
- ALWAYS resolve a changed `*Screen.kt` to its Figma frame through `docs/screens-map.md` (`Flow › Frame` on the latest `Bitkit - Handoff vNN` page). When adding or removing a `*Screen.kt`, add or drop its row there (`todo` when the design does not exist yet); `ScreensMapTest` fails otherwise
- ALWAYS fill the PR `### Design` section:
  - User-visible UI changes with an existing design: link the relevant Figma frames. Start with `docs/screens-map.md` for mapped screens; link known handoff frames directly for sheets, dialogs, reusable views, and other UI outside the map.
  - UI changes mapped to `todo` or `n/a`, or other UI changes without an available design, including new features: use `N/A — no design available.`; creating a design is never required.
  - Changes without user-visible UI changes: use `N/A — no UI changes.`
  - Genuinely uncertain frame matches: report the uncertainty honestly; never invent links.
- Code review may make at most one advisory request per PR when an existing-design UI link is omitted or an out-of-map `N/A — no design available.` claim is unverified. Valid mapped `todo`/`n/a` cases and `N/A — no UI changes.` require no request. Missing links never block approval, CI, PR creation, or review readiness.
- ALWAYS ensure proper error handling in coroutines
- ALWAYS acknowledge datastore async operations run synchronously in a suspend context
- NEVER use `runBlocking` in suspend functions
- ALWAYS pass the TAG as context to `Logger` calls, e.g. `Logger.debug("message", context = TAG)`
- NEVER add `e = ` named parameter to Logger calls
- NEVER manually append the `Throwable`'s message or any other props to the string passed as the 1st param of `Logger.*` calls, its internals are already enriching the final log message with the details of the `Throwable` passed via the `e` arg
- ALWAYS wrap parameter values in log messages with single quotes, e.g. `Logger.info("Received event '$eventName'", context = TAG)`
- ALWAYS start log messages with a verb, e.g. `Logger.info("Received payment for '$hash'", context = TAG)`
- ALWAYS keep log names, tags, labels, and references mechanically traceable to the caller. Do not rewrite them into long descriptions.
- ALWAYS log errors at the final handling layer where the error is acted upon, not in intermediate layers that just propagate it
- ALWAYS use the Result API instead of try-catch
- NEVER wrap methods returning `Result<T>` in try-catch
- ALWAYS use `runSuspendCatching` (from `ext/Coroutines.kt`) instead of `runCatching` when the block calls suspend functions or runs in a coroutine — it re-throws `CancellationException` so structured-concurrency cancellation is preserved; plain `runCatching` catches `Throwable` and swallows it. NEVER log a `CancellationException` as an error
- EXCEPTION: when a `TimeoutCancellationException` from `withTimeout` must be treated as a retriable failure, use `runCatching` with an explicit `if (it is CancellationException && it !is TimeoutCancellationException) throw it` guard (e.g. `BlocktankRepo.refreshCjitEntries`)
- PREFER to use `it` instead of explicit named parameters in lambdas e.g. `fn().onSuccess { log(it) }.onFailure { log(it) }`
- NEVER inject ViewModels as dependencies - Only android activities and composable functions can use viewmodels
- ALWAYS co-locate screen-specific ViewModels in the same package as their screen; only place ViewModels in `viewmodels/` when shared across multiple screens
- NEVER hardcode strings and always preserve string resources
- ALWAYS localize in ViewModels using injected `@ApplicationContext`, e.g. `context.getString()`
- ALWAYS use `remember` for expensive Compose computations
- ALWAYS declare `modifier: Modifier = Modifier,` as the FIRST optional parameter in composable declarations
- ALWAYS pass `modifier = ...` as the LAST argument in composable calls
- ALWAYS add trailing commas in multi-line declarations, EXCEPT after a `modifier = ...` last argument — never add a trailing comma there, whether the modifier is a single call (`modifier = Modifier.weight(1f)`) or a chain (`modifier = Modifier.fillMaxWidth().testTag("foo")`)
- ALWAYS use `navController.navigateTo(route)` for simple navigation; NEVER use raw `navController.navigate(route)` — `navigateTo` prevents duplicate destinations
- ALWAYS prefer `VerticalSpacer`, `HorizontalSpacer`, `FillHeight` and `FillWidth` over `Spacer` when applicable
- PREFER declaring small dependant classes, constants, interfaces or top-level functions in the same file with the core class where these are used
- ALWAYS create data classes for state AFTER viewModel class in same file
- ALWAYS return early where applicable, PREFER guard-like `if` conditions like `if (condition) return`
- USE `docs/` as target dir of saved files when asked to create documentation for new features
- NEVER write code in the documentation files
- NEVER add code comments to private functions, classes, etc
- ALWAYS use `/** */` to document constants
- ALWAYS use `_uiState.update { }`, NEVER use `_stateFlow.value =`
- ALWAYS add the warranted changes in unit tests to keep the unit tests succeeding
- ALWAYS follow the patterns of the existing code in `app/src/test` when writing new unit tests
- ALWAYS be mindful of thread safety when working with mutable lists & state
- ALWAYS split screen composables into parent accepting viewmodel + inner private child accepting state and callbacks `Content()`
- ALWAYS preview an in-sheet screen as `BottomSheetPreview { Content(modifier = Modifier.sheetHeight()) }`, passing the host's `SheetSize` when it isn't the default `LARGE`; see `SendErrorScreen.kt`
- ALWAYS write Compose `testTag`s in PascalCase (e.g. `HwPairedFinish`), never snake_case
- ALWAYS name lambda parameters in a composable function using present tense, NEVER use past tense
- ALWAYS use `whenever { mock.suspendCall() }` for suspend stubs if not inside `test{}` fn blocks
- ALWAYS use `whenever(mock.call())` for non-suspend stubs and for suspend stubs if inside `test{}` fn blocks
- NEVER use the old, deprecated `wheneverBlocking`
- ALWAYS prefer `kotlin.test` asserts over `org.junit.Assert` in unit tests
- ALWAYS use a deterministic locale in unit tests to ensure consistent results across CI and local runs
- ALWAYS add a locale parameter with default value `Locale.getDefault()` to methods that depend on locale
- ALWAYS add business logic to repository layer via methods returning `Result<T>` and use it in ViewModels
- ALWAYS order upstream architectural data flow this way: `UI -> ViewModel -> Repository -> RUST` and vice versa for downstream
- ALWAYS add new localizable string resources in alphabetical order in `strings.xml`
- NEVER add string resources for strings used only in dev settings screens and previews and never localize acronyms
- ALWAYS use template in `.github/pull_request_template.md` for PR descriptions
- ALWAYS reference test files in PR descriptions/QA Notes by bare file name only (e.g. `HwWalletRepoTest.kt`), NEVER the full path; only when two referenced test files share the same name, prefix the shortest leading path segment(s) that disambiguate them (e.g. `repositories/FooTest.kt` vs `viewmodels/FooTest.kt`)
- ALWAYS wrap `ULong` numbers with `USat` in arithmetic operations, to guard against overflows
- PREFER to use one-liners with `run {}` when applicable, e.g. `override fun someCall(value: String) = run { this.value = value }`
- ALWAYS add imports instead of inline fully-qualified names
- PREFER to place `@Suppress()` annotations at the narrowest possible scope
- ALWAYS wrap suspend functions in `withContext(ioDispatcher)` if in domain layer, using ctor injected prop `@IoDispatcher private val ioDispatcher: CoroutineDispatcher`
- ALWAYS position `companion object` at the top of the class
- NEVER use `Exception` directly, use `AppError` instead
- ALWAYS inherit custom exceptions from `AppError`
- ALWAYS prefer `requireNotNull(someNullable) { "error message" }` or `checkNotNull { "someErrorMessage" }` over `!!` or `?: SomeAppError()`
- ALWAYS prefer Kotlin `Duration` for timeouts and delays
- ALWAYS prefer `when (subject)` with Kotlin guard conditions (`if`) over condition-based `when {}` with `is` type checks, e.g. `when (event) { is Foo if event.x == y -> ... }` instead of `when { event is Foo && event.x == y -> ... }`
- ALWAYS prefer `sealed interface` over `sealed class` when no shared state or constructor is needed
- NEVER duplicate error logging in `.onFailure {}` if the called method already logs the same error internally
- ALWAYS use `ImmutableList`/`ImmutableMap`/`ImmutableSet` instead of `List`/`Map`/`Set` for composable function parameters and UiState data class fields
- ALWAYS annotate UiState data classes with `@Immutable`; use `@Stable` instead when any field holds a non-immutable type (e.g. `Throwable`, external library types from `bitkitcore`/`ldknode`/`vssclient`, or types containing plain `List`/`Map`/`Set`)
- ALWAYS use `.toImmutableList()`, `.toImmutableMap()`, `.toImmutableSet()` when producing collections for UI state
- ALWAYS use `persistentListOf()`, `persistentMapOf()`, `persistentSetOf()` for default values in UiState fields

### Changelog

- NEVER edit `CHANGELOG.md` in normal feature/fix PRs; release automation collects changelog fragments into it
- ALWAYS add exactly ONE changelog fragment for user-facing `feat:` and `fix:` PRs; skip for `chore:`, `ci:`, `refactor:`, `test:`, `docs:` unless the change is user-facing
- PUT normal release fragments in `changelog.d/next/` and hotfix fragments in `changelog.d/hotfix/`
- NAME fragments `<issue-or-pr>.<category>.md`, where category is one of `added`, `changed`, `deprecated`, `removed`, `fixed`, or `security`
- WRITE the fragment as one polished user-facing sentence without a leading bullet and without a PR number
- NEVER add multiple changelog fragments for the same PR — summarize all changes in one concise fragment
- Release commits consume fragments with `scripts/collect-changelog.sh --target next|hotfix`, update `CHANGELOG.md`, and delete consumed fragment files
- NEVER modify released version sections manually

### Device Debugging (adb)

- App IDs per flavor: `to.bitkit.dev` (dev/regtest), `to.bitkit.tnet` (testnet), `to.bitkit` (mainnet)
- ALWAYS use `adb shell "run-as to.bitkit.dev ..."` to access the app's private data directory (debug builds only)
- App files root: `files/` (relative, inside `run-as` context)
- Key paths:
  - `files/logs/` — app log files (e.g. `bitkit_2026-02-09_21-04-16.log`)
  - `files/bitcoin/wallet0/ldk/` — LDK node storage (graph cache, dumps)
  - `files/bitcoin/wallet0/core/` — bitkit-core storage
  - `files/datastore/` — DataStore preferences and JSON stores
- To read a file: `adb shell "run-as to.bitkit.dev cat files/logs/bitkit_YYYY-MM-DD_HH-MM-SS.log"`
- To list files: `adb shell "run-as to.bitkit.dev ls -la files/logs/"`
- To find files: `adb shell "run-as to.bitkit.dev find files/ -name '*.log' -o -name '*.txt'"`
- ALWAYS download device files to `.ai/{name}_{timestamp}/` when needed for debugging (e.g. `.ai/logs_1770671066/`)
- To download: `adb shell "run-as to.bitkit.dev cat files/path/to/file" > .ai/folder_timestamp/filename`
- ALWAYS try reading device logs automatically via adb BEFORE asking user to provide log files
- NEVER type long strings with `adb shell input text` — it silently drops characters (it lost 54 of a
  397-character invoice), and `adb shell cmd clipboard` is not implemented on the emulator image.
  Hand an address or invoice to the app as a URI instead, which also skips the recipient screen:
  `adb shell am start -a android.intent.action.VIEW -d "lightning:<invoice>" to.bitkit.dev`
- For short strings that must be typed, enter digit groups and separators separately, then verify —
  dotted strings such as host IPs are where the dropping shows up first

### Architecture Guidelines

- Use `LightningNodeService` to manage background notifications while the node is running
- Use `LightningService` to wrap node's RUST APIs and manage the inner lifecycle of the node
- Use `LightningRepo` to defining the business logic for the node operations, usually delegating to `LightningService`
- Use `WakeNodeWorker` to manage the handling of remote notifications received via cloud messages
- Use `*Services` to wrap rust library code exposed via bindings
- Use CQRS pattern of Command + Handler like it's done in the `NotifyPaymentReceived` + `NotifyPaymentReceivedHandler` setup
