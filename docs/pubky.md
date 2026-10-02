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

Session, state, key and publishing calls are serialized by `PaykitSdkService`'s operation lock. The public reads — `fetchFile()` (`fetchPubkyFileBounded()`), `fetchPubkyProfile()`, `fetchPubkyFollows()`, `resolveContactProfile()`, and the receiver reads `discoverRelevantReceiverPaths()`, `privateReceiverPathSelection()` and `paymentRequestReceiverPaths()` (`paykitReceiverPaths()` and `paykitReceiverMarker()`) — run outside that lock, at most 6 at once, and are cancelled with their caller. Only unauthenticated public reads may use that path. Reading or saving a contact record stays under the lock, so a caller that discovers receiver paths and then saves them takes the lock only for the save. A wallet wipe still applies to the public reads: one that needs an SDK instance builds it under the lock, one that starts during the wipe fails the way a locked call does, and one that the wipe overtakes fails instead of returning its result, while the wipe's own cleanup can still read.

Each public read names a lane. An interactive read, for something the user is looking at or waiting on — the user's own profile, avatars and other files, Pubky Ring choice rows, the follow lookups of `prepareImport()` that an adopted Ring row waits on, a single contact opened from Add Contact or the contact screen, and the receiver reads for one contact the user pays or sends a payment request to — takes one of the 6 read slots. A bulk read — the contacts list's background profile refresh, private sync's receiver discovery and marker reads, and the payment request target refresh over all saved contacts — first takes one of 4 bulk slots and then a read slot, so bulk work never holds more than 4 read slots and at least 2 stay free for interactive reads. Both are first come, first served, and a cancelled read gives its slots back.

## Repository Layer (`PubkyRepo`)

Manages session lifecycle, identity adoption, and profile data. Singleton scoped.

### Initialization

- `PubkyRepo` self-initializes via `init {}` block — no external trigger needed
- `AppViewModel` injects `PubkyRepo` to ensure Hilt creates it at app startup
- `initialize()` attempts to restore any saved session via `importSession()`
- If restoration fails, saved credentials and cached profile data remain available for retry
- Recovery retries on connectivity restoration and app resume, and an adopted Ring credential can re-sign in the same identity
- A `pubkyauth://` authorization deep link waits for `awaitIdentityReady()` as well as the first restore, so a link that cold-starts Bitkit after the first restore failed waits for the retry instead of reporting a missing identity. When no session is active, `awaitIdentityReady()` retries the restore once on the same lock as the resume retry, adoption, identity creation and backup restore, so it waits for one already running and never restores twice. It returns `Ready`, `Missing` (no saved identity, decided without a network call) or `Unavailable` (a saved identity whose session could not be restored yet; its credentials, cached profile and the session-expired flag are left alone). The retry runs in the repository scope, so a newer scan that supersedes the link cannot interrupt a half-done `importSession()`, and the profile and contacts it loads are not awaited
- An authorization request that finds no active session shows "Pubky Identity Required" only when no identity is saved. With a saved identity that could not be restored, the deep link and the scanner both show a retryable "Couldn't Load Your Pubky Profile" error instead

### Profile Loading

- `loadProfile()` fetches the profile for the authenticated public key
- Uses a `Mutex` to serialize loads, then re-checks the captured identity before fetching
- Re-checks `_publicKey` after the network call to guard against a concurrent `signOut()`
- Profile name and image URI are cached in `PubkyStore` (DataStore) for instant display on launch before the full profile loads
- The cache also records the public key it was taken from (`cachedProfileOwner`). Only the profile cache writes that field, and it is cleared together with the cached name and image URI
- `ProfileScreen` shows the cached name and avatar with an inline loading indicator while a load is in flight, but only when the cached owner matches the current public key. `ProfileViewModel` makes this decision and exposes the cached profile only under those conditions. Editing, tags and the other profile actions wait for the loaded profile, and a failed load still shows the retry state
- Opening `ProfileScreen` always starts a background refresh, so tag and profile edits build on the latest published profile once it completes. An already loaded profile stays on screen while it runs, and a result overtaken by a save, deletion or identity change is dropped. When a load is already in flight, such as the startup load, the screen waits for it instead of queueing a second load, then loads once more only if it ended without a profile for the current public key and the user is still signed in. Retry always reloads

### Exposed State

| StateFlow | Description |
|---|---|
| `profile` | Full `PubkyProfile` or null |
| `publicKey` | Authenticated user's public key |
| `isAuthenticated` | True while a public key is set |
| `displayName` | Profile name with cached fallback |
| `displayImageUri` | Profile image URI with cached fallback |
| `cachedProfile` | Cached profile name and image URI with the public key they belong to |
| `isLoadingProfile` | Loading indicator |
| `contacts` | List of followed `PubkyProfile` contacts |
| `isLoadingContacts` | Contacts loading indicator |
| `isImportingContacts` | True while a contact import runs |

### Contacts

- `loadContacts()` reads the saved Paykit contact records and publishes them at once, without waiting for any profile lookup. Each row uses, in order, the user's profile override, the record's stored Paykit profile, a profile resolved earlier in this session, then the record's label. A record with neither an override nor a stored profile is then refreshed in the background on the bulk read lane via `resolveContactProfile()`, and its row updates when the lookup finishes. A row the user edited or removed meanwhile is left alone, and repeated loads share one refresh while it covers the same contacts
- `isLoadingContacts`, `contactsLoadVersion` and `contactsLoadCompletionVersion` describe the saved records only: a load counts as finished once they are published. Background row updates change neither the version counters nor the set of contact keys, so the private Paykit sync observer in `AppViewModel`, which keys on the contact keys and whether contacts have loaded, does not run again for them
- The session profiles are kept in memory per identity. `prepareImport()`, `importContacts()`, `addContact()` and background refreshes add to them; sign-out, wipe, profile deletion, backup restore and a switch to another identity clear them and stop a running refresh
- `ContactsScreen` shows its full-screen spinner only until the saved records first load for the current identity
- Contact keys from the FFI may lack the `pubky` prefix; `ensurePubkyPrefix()` normalizes them before profile resolution
- `prepareImport()` discovers followed keys via `fetchPubkyFollows()` and leaves out the identity's own key, also when it is spelled with other final z-base32 padding bits, then resolves each profile once on the interactive read lane, since the Pubky Ring choice row waits on it. A follow it cannot resolve, such as a key that never published a profile, stays in the preview as a placeholder under its truncated public key
- `importContacts()` saves the profiles `prepareImport()` resolved one at a time, labelled with the name the preview shows, without a profile lookup or receiver discovery, so a placeholder is saved under its truncated public key. Each record starts with only the wallet receiver path: private sync (`PrivatePaykitRepo`) discovers a saved contact's receiver paths and merges them into the record before it uses them, and nothing else uses them for payments. Removing a contact blocks the record's receiver paths and those of its linked peers, so a contact private sync never reached blocks only the wallet path. A contact already in the contacts list is skipped. When a save fails, the import keeps the contacts already saved, carries on with the rest and then fails, so the import screen stays open with an error toast and a retry saves only the missing contacts
- The import runs in `PubkyRepo`'s scope, so leaving the import screens does not stop it half way, and it stops saving once the identity changes. Each save checks the identity again under the Paykit SDK lock, so a save queued behind a sign-in to another identity is not written to that identity. `isImportingContacts` is true while an import runs, and the import screens disable Select, Import All and Continue meanwhile, and a second tap on Import All or Continue does not start a second import. A successful import clears the pending import itself, so the import overview moves on to Pay Contacts when an import started from the selection screen finishes after the user went back to it
- A contact without a resolved profile still appears in the list under its label, or its truncated public key when it has none
- `resolvePendingContactProfile()` looks up a saved contact on the interactive read lane while its row still shows only its label because the background refresh has not finished looking it up. That lookup takes the contact over from the refresh: the refresh cancels its own lookup of the contact, so one still queued behind the bulk read lane never runs, and never applies a result for it. A second caller waits for the same lookup, which a later refresh replacing the first leaves running; only a sign-out or an identity change stops it. When the lookup fails or finds no profile, the row keeps its label and the call returns at once rather than waiting for the refresh. The contact screen calls it when it opens and a tag change waits for it before saving; Edit Contact keeps its form loading until it returns. An edit therefore keeps the contact's avatar, which the edit form cannot set, and starts from its bio and links whenever the lookup finds them; when the lookup fails, the edit saves the row as it shows. Once the user changes a field, Edit Contact stops applying later updates of the contact to the form
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

Avatars use a two-tier cache:

1. **Memory** — Coil's `MemoryCache` (15% of app memory), managed by Coil
2. **Disk** — Coil's `DiskCache` in `cacheDir/pubky-images/`, read and written by `PubkyImageFetcher`. Coil only fills the disk cache from its own network fetcher, so the Pubky fetcher manages this directory itself

Disk cache rules:

- Entries are keyed by the request's disk cache key, or by the original `pubky://` URI when none is set. For a file descriptor this is the descriptor URI, not the blob `src`
- Reads and writes follow the request's disk cache policy
- Only a fully successful fetch is written: a raw image, or a descriptor whose blob was fetched. A descriptor whose blob fetch failed, and JSON without a Pubky `src`, are never written
- A failed write discards the entry, and the fetched image is still displayed
- Sign-out and a switch to another identity clear the whole directory, because only this fetcher uses it. Sign-out also removes `pubky://` entries from the memory cache
- Each clear first advances `PubkyImageCacheEpoch`. The fetcher reads it before going to the network and commits its write only while it is unchanged, so a fetch in flight across a clear does not re-populate the cleared directory
- Avatars are public data, and the directory is app-private

### Loading Flow

1. Coil checks memory cache → return if hit
2. `PubkyImageFetcher.fetch()` checks the disk cache → return if hit, without waiting for Paykit setup or using the network
3. On a miss, the fetcher limits the successful response body to 1 MiB
4. If the response is a JSON file descriptor with a Pubky `src`, follow the indirection with the same limit
5. The fetcher writes a fully successful result to the disk cache unless the directory was cleared during the fetch, then Coil decodes it and caches it in memory

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
| `data/PubkyImageCacheEpoch.kt` | Clear counter that keeps in-flight fetches out of a cleared disk cache |
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
