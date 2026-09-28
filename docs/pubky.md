# Pubky Integration

Paykit issuers should follow the [Paykit issuer interoperability contract](paykit-issuer-interoperability.md) for payment request and endpoint shapes accepted by Bitkit.

## Overview

Bitkit integrates [Pubky](https://pubky.org) decentralized identity. A user either creates a pubky in Bitkit or adopts one that [Pubky Ring](https://pubky.org) already owns, read through the shared pubky content provider. Once an identity is active, the user's profile name and avatar appear on the home screen header, a full profile page shows their bio, links, and a shareable QR code, and the contacts screen shows followed Pubky users.

## Identity Flow

```
ProfileIntroScreen → PubkyChoiceScreen → CreateProfileScreen → ProfileScreen
```

1. **ProfileIntroScreen** — presents the Pubky feature and a "Continue" button
2. **PubkyChoiceScreen** — lists the pubkys Pubky Ring owns, or offers creating one in Bitkit when there are none
3. **CreateProfileScreen** — signs the identity up on a homeserver and publishes the profile
4. **ProfileScreen** — displays the active identity's profile (name, bio, links, QR code)

An adopted Ring identity keeps its secret in Pubky Ring: Bitkit stores only the reference and reads the
secret just-in-time for signing. Bitkit still acts as an authenticator for incoming `pubkyauth://`
requests, which are approved from the Pubky auth approval sheet.

## Service Layer (`PubkyService`)

Delegates Pubky operations to `PaykitSdkService`, which uses:

- **paykit-ffi** (`com.synonym:paykit-android`) — session management, auth approval, profile/contact resolution, and bounded file fetching
  - `fetchPubkyProfile()`, `fetchPubkyFollows()`, `resolveContactProfile()`, `fetchPubkyFileBounded()`
- **bitkit-core** (`com.synonym:bitkit-core-android`) — mnemonic-to-seed conversion for receiver noise-key derivation
  - `mnemonicToSeed()`

All calls are dispatched on `ServiceQueue.CORE` (single-thread executor) to ensure serial access to the underlying Rust state.

## Repository Layer (`PubkyRepo`)

Manages session lifecycle, identity adoption, and profile data. Singleton scoped.

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
| `isAuthenticated` | True while a public key is set |
| `displayName` | Profile name with cached fallback |
| `displayImageUri` | Profile image URI with cached fallback |
| `isLoadingProfile` | Loading indicator |
| `contacts` | List of followed `PubkyProfile` contacts |
| `isLoadingContacts` | Contacts loading indicator |

### Contacts

- `loadContacts()` reads saved Paykit contact records, then concurrently resolves any missing profiles via `resolveContactProfile()`
- Contact keys from the FFI may lack the `pubky` prefix; `ensurePubkyPrefix()` normalizes them before profile resolution
- `prepareImport()` discovers followed keys via `fetchPubkyFollows()`, then resolves each profile
- If a contact profile fetch fails, a `PubkyProfile.placeholder()` is used to ensure the contact still appears in the list with a truncated public key
- `fetchContactProfile()` fetches a single contact's profile on demand (used by the detail screen)

## Contacts Flow

```
ContactsIntroScreen → (if authenticated) ContactsScreen → ContactDetailScreen
                     → (if not authenticated) PubkyChoiceScreen → ContactsScreen
```

1. **ContactsIntroScreen** — presents the contacts feature with a "Continue" button; marks `hasSeenContactsIntro` in settings
2. **ContactsScreen** — displays a searchable, alphabetically grouped list of followed Pubky users
3. **ContactDetailScreen** — shows a contact's profile details (name, bio, links) with copy and share actions

## PubkyImage Component

Composable for loading and displaying images from `pubky://` URIs, backed by Coil 3.

### Architecture

- `PubkyImage` is a stateless composable wrapping Coil's `AsyncImage`
- `PubkyImageFetcher` is a Coil `Fetcher` that handles `pubky://` URIs via Paykit's bounded file fetch
- `ImageModule` provides a singleton `ImageLoader` with `PubkyImageFetcher.Factory`, memory cache, and disk cache

### Caching Strategy (Coil)

Coil manages a two-tier cache automatically:

1. **Memory** — Coil's `MemoryCache` (15% of app memory)
2. **Disk** — Coil's `DiskCache` in `cacheDir/pubky-images/`

### Loading Flow

1. Coil checks memory cache → return if hit
2. Coil checks disk cache → return if hit
3. `PubkyImageFetcher.fetch()` limits the successful response body to 1 MiB
4. If the response is a JSON file descriptor with a Pubky `src`, follow the indirection with the same limit
5. Coil decodes and caches the result

The bound is enforced while successful response bodies are read, before the bytes cross the FFI boundary. HTTP error
bodies can still be buffered by the Pubky client before Paykit regains control.

### Display States

- **Loading** — `CircularProgressIndicator`
- **Loaded** — circular-clipped image (handled by Coil's success state)
- **Error** — fallback user icon on gray background

## Domain Model (`PubkyProfile`)

- `publicKey`, `name`, `bio`, `imageUrl`, `links`, `status`
- `truncatedPublicKey` — uses `String.ellipsisMiddle()` extension
- `PubkyProfileLink` — `label` + `url` pair
- `fromPubkyProfile()` and `fromPaykitProfile()` — map Paykit SDK profile types
- `placeholder()` — creates a stub profile with the truncated public key as the name

## Home Screen Integration

- `HomeViewModel` observes `PubkyRepo.displayName` and `PubkyRepo.displayImageUri`
- The home screen header shows the profile name and avatar when authenticated
- The `PROFILE` suggestion card is auto-dismissed when the user is authenticated

## Key Files

| File | Purpose |
|---|---|
| `services/PubkyService.kt` | FFI wrapper |
| `repositories/PubkyRepo.kt` | Session management and identity adoption |
| `data/sharedpubky/SharedPubkyClient.kt` | Reads Pubky Ring's shared pubky provider |
| `data/PubkyImageFetcher.kt` | Coil fetcher for pubky:// URIs |
| `di/ImageModule.kt` | Hilt module providing ImageLoader |
| `data/PubkyStore.kt` | DataStore for cached profile metadata |
| `models/PubkyProfile.kt` | Domain model |
| `ui/components/PubkyImage.kt` | Image composable |
| `ui/screens/profile/ProfileIntroScreen.kt` | Intro screen |
| `ui/screens/profile/PubkyChoiceScreen.kt` | Identity choice screen |
| `ui/screens/profile/PubkyChoiceViewModel.kt` | Identity choice ViewModel |
| `ui/screens/profile/ProfileScreen.kt` | Profile display |
| `ui/screens/profile/ProfileViewModel.kt` | Profile ViewModel |
| `ui/screens/contacts/ContactsIntroScreen.kt` | Contacts intro screen |
| `ui/screens/contacts/ContactsScreen.kt` | Contacts list |
| `ui/screens/contacts/ContactsViewModel.kt` | Contacts list ViewModel |
| `ui/screens/contacts/ContactDetailScreen.kt` | Contact detail display |
| `ui/screens/contacts/ContactDetailViewModel.kt` | Contact detail ViewModel |
