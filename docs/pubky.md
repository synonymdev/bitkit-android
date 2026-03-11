# Pubky Integration

## Overview

Bitkit integrates [Pubky](https://pubky.org) decentralized identity, allowing users to connect their Pubky profile via [Pubky Ring](https://play.google.com/store/apps/details?id=to.pubky.ring) authentication. Once connected, the user's profile name and avatar appear on the home screen header, a full profile page shows their bio, links, and a shareable QR code, and the contacts screen shows followed Pubky users.

## Auth Flow

```
ProfileIntroScreen → PubkyRingAuthScreen → ProfileScreen
```

1. **ProfileIntroScreen** — presents the Pubky feature and a "Continue" button
2. **PubkyRingAuthScreen** — initiates authentication via Pubky Ring deep link (`pubkyauth://`), waits for approval via relay, then completes session import
3. **ProfileScreen** — displays the authenticated user's profile (name, bio, links, QR code)

### Deep Link Flow

The auth handshake uses a relay-based protocol:

1. `PubkyService.startAuth()` generates a `pubkyauth://` URL with required capabilities
2. The URL is opened via `ACTION_VIEW` intent, launching Pubky Ring
3. Pubky Ring prompts the user to approve the requested capabilities
4. `PubkyService.completeAuth()` blocks on the relay until Ring sends approval, returning a session secret
5. `PubkyService.importSession()` activates the session, returning the user's public key
6. The session secret is persisted in Keychain for restoration on next launch

### Auth State Machine (`PubkyAuthState`)

- **Idle** — no authentication in progress
- **Authenticating** — `startAuth()` has been called, waiting for relay setup
- **Authenticated** — session active, profile available

## Service Layer (`PubkyService`)

Wraps two FFI libraries:

- **paykit-ffi** (`com.synonym:paykit-android`) — session management
  - `paykitInitialize()`, `paykitImportSession()`, `paykitSignOut()`, `paykitForceSignOut()`
- **bitkit-core** (`com.synonym:bitkit-core-android`) — auth relay, profile/contacts fetching, and file fetching
  - `startPubkyAuth()`, `completePubkyAuth()`, `cancelPubkyAuth()`, `fetchPubkyProfile()`, `fetchPubkyContacts()`, `fetchPubkyFile()`

All calls are dispatched on `ServiceQueue.CORE` (single-thread executor) to ensure serial access to the underlying Rust state.

## Repository Layer (`PubkyRepo`)

Manages auth state, session lifecycle, and profile data. Singleton scoped.

### Initialization

- `PubkyRepo` self-initializes via `init {}` block — no external trigger needed
- `AppViewModel` injects `PubkyRepo` to ensure Hilt creates it at app startup
- `initialize()` attempts to restore any saved session via `importSession()`
- If restoration fails, the stale keychain entry is deleted to allow a clean retry
- Session secret is only persisted **after** `importSession()` succeeds to avoid stale entries on failure

### Profile Loading

- `loadProfile()` fetches the profile for the authenticated public key
- Uses a `Mutex` with `tryLock()` to prevent concurrent loads (skips if already loading)
- Re-checks `_publicKey` after the network call to guard against a concurrent `signOut()`
- Profile name and image URI are cached in `PubkyStore` (DataStore) for instant display on launch before the full profile loads

### Exposed State

| StateFlow | Description |
|---|---|
| `profile` | Full `PubkyProfile` or null |
| `publicKey` | Authenticated user's public key |
| `isAuthenticated` | Derived from internal auth state |
| `displayName` | Profile name with cached fallback |
| `displayImageUri` | Profile image URI with cached fallback |
| `isLoadingProfile` | Loading indicator |
| `contacts` | List of followed `PubkyProfile` contacts |
| `isLoadingContacts` | Contacts loading indicator |

### Contacts

- `loadContacts()` fetches the authenticated user's contact keys via `fetchPubkyContacts`, then concurrently fetches each contact's profile
- Contact keys from the FFI may lack the `pubky` prefix; `ensurePubkyPrefix()` normalizes them before passing to `fetchPubkyProfile`
- If a contact profile fetch fails, a `PubkyProfile.placeholder()` is used to ensure the contact still appears in the list with a truncated public key
- `fetchContactProfile()` fetches a single contact's profile on demand (used by the detail screen)

## Contacts Flow

```
ContactsIntroScreen → (if authenticated) ContactsScreen → ContactDetailScreen
                     → (if not authenticated) PubkyRingAuthScreen → ContactsScreen
```

1. **ContactsIntroScreen** — presents the contacts feature with a "Continue" button; marks `hasSeenContactsIntro` in settings
2. **ContactsScreen** — displays a searchable, alphabetically grouped list of followed Pubky users
3. **ContactDetailScreen** — shows a contact's profile details (name, bio, links) with copy and share actions

## PubkyImage Component

Composable for loading and displaying images from `pubky://` URIs, backed by `PubkyImageViewModel`.

### Architecture

- `PubkyImageViewModel` manages image state as a `Map<String, PubkyImageState>` keyed by URI
- The composable calls `viewModel.loadImage(uri)` via `LaunchedEffect`
- Image fetching is delegated to `PubkyRepo.fetchImage()` which handles cache lookup and network fetching
- Business logic follows `UI -> ViewModel -> Repository -> RUST` flow

### Caching Strategy (`PubkyImageCache`)

Two-tier cache:

1. **Memory** — `ConcurrentHashMap<String, Bitmap>` for instant access
2. **Disk** — files in `cacheDir/pubky-images/`, keyed by SHA-256 hash of the URI

### Loading Flow

1. Check memory cache → return if hit
2. Check disk cache → decode, populate memory, return if hit
3. Fetch via `PubkyRepo.fetchImage(uri)` which delegates to `PubkyService.fetchFile()`
4. If response is a JSON file descriptor with a `src` field, follow the indirection and fetch the blob
5. Decode and store via `PubkyImageCache.decodeAndStore()`

### Display States (`PubkyImageState`)

- **Loading** — `CircularProgressIndicator`
- **Loaded** — circular-clipped `Image`
- **Failed** — fallback user icon on gray background

## Domain Model (`PubkyProfile`)

- `publicKey`, `name`, `bio`, `imageUrl`, `links`, `status`
- `truncatedPublicKey` — uses `String.ellipsisMiddle()` extension
- `PubkyProfileLink` — `label` + `url` pair
- `fromFfi()` — maps from bitkitcore's `PubkyProfile` FFI type
- `placeholder()` — creates a stub profile with the truncated public key as the name

## Home Screen Integration

- `HomeViewModel` observes `PubkyRepo.displayName` and `PubkyRepo.displayImageUri`
- The home screen header shows the profile name and avatar when authenticated
- The `PROFILE` suggestion card is auto-dismissed when the user is authenticated

## Key Files

| File | Purpose |
|---|---|
| `services/PubkyService.kt` | FFI wrapper |
| `repositories/PubkyRepo.kt` | Auth state and session management |
| `data/PubkyImageCache.kt` | Two-tier image cache |
| `data/PubkyStore.kt` | DataStore for cached profile metadata |
| `models/PubkyProfile.kt` | Domain model |
| `ui/components/PubkyImage.kt` | Image composable |
| `ui/screens/profile/ProfileIntroScreen.kt` | Intro screen |
| `ui/screens/profile/PubkyRingAuthScreen.kt` | Auth screen |
| `ui/screens/profile/PubkyRingAuthViewModel.kt` | Auth ViewModel |
| `ui/screens/profile/ProfileScreen.kt` | Profile display |
| `ui/screens/profile/ProfileViewModel.kt` | Profile ViewModel |
| `ui/screens/contacts/ContactsIntroScreen.kt` | Contacts intro screen |
| `ui/screens/contacts/ContactsScreen.kt` | Contacts list |
| `ui/screens/contacts/ContactsViewModel.kt` | Contacts list ViewModel |
| `ui/screens/contacts/ContactDetailScreen.kt` | Contact detail display |
| `ui/screens/contacts/ContactDetailViewModel.kt` | Contact detail ViewModel |
