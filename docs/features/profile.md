# Profile

The user's Pubky identity and public profile: create one in Bitkit or adopt one from Pubky Ring, edit and delete the profile, share payment data with contacts, and approve `pubkyauth://` requests from other apps.

## What it does
- Create: `PubkyChoiceScreen` offers "Create profile with Bitkit" (`PubkyChoiceCreate`) when Pubky Ring exposes no identities. `CreateProfileScreen` takes name, bio, links, tags and avatar, signs the wallet-derived identity up on a homeserver (Homegate `POST /ip_verification` for the signup code) and publishes the profile. If the derived key already has a remote profile, the screen is titled "Restore Profile" and prefilled.
- Adopt a Ring identity: with Pubky Ring installed (same signing key), the choice screen lists its identities (`PubkyChoiceIdentity`, spinner `PubkyChoiceIdentityLookup` while a row's profile loads). Tapping one adopts it. The secret stays in Ring; Bitkit stores a reference. Result: no profile leads to `CreateProfile`; a profile with follows leads to the contact import (`contacts.md`); otherwise `PayContacts`.
- Pay Contacts (`PayContactsScreen`): one Continue button (`PayContactsContinue`) that enables contact payments (publishes endpoints, see `contacts.md`), then opens Profile. Failure shows an error toast and stays.
- Profile (`ProfileScreen`): name, bio, links, tags, pubky QR (`ProfileQRCode`), Copy, Share, Edit. While the profile loads it shows a cached read-only header, a spinner (`ProfileLoading`) before the session is restored, or on failure a retry state with Retry and Disconnect.
- Edit (`EditProfileScreen`): name, bio, avatar, links, tags, Save, Cancel and Delete Profile at the bottom. Delete wipes the profile on the homeserver and returns to `PubkyChoice` (`ProfileIntro` if the intro was never seen). If delete fails, a dialog offers Retry or Disconnect.
- Disconnect (`ProfileEmptySignOut` in the failed-load state): forgets the identity locally; the remote profile stays.
- Home: the header shows the profile name and avatar (`ProfileButton`); the `PROFILE` suggestion card (`Suggestion-profile`) is hidden once authenticated.
- Pubky Auth approval (`PubkyAuthApprovalSheet`, `Sheet.PubkyAuth`): approves a `pubkyauth://` sign-in or signup request. States: loading, watch-only consent, authorize, local auth (PIN or biometrics when enabled), authorizing, success. Companion claims (Paykit access, watch-only account) are specified in `docs/pubky-auth-companion-claims.md`.

## How a user reaches it
- Header profile button `ProfileButton` or drawer `DrawerProfile` (`DrawerMenu.kt`). `profileDestination` in `app/src/main/java/to/bitkit/ui/ContentView.kt`: identity exists opens `Profile`; no identity opens `PubkyChoice` if the intro was seen, else `ProfileIntro` (`ProfileIntro`, `ProfileIntro-button`) then `PubkyChoice`.
- `Routes.CreateProfile`, `Routes.EditProfile` (`ProfileEdit`), `Routes.PayContacts` follow the flow above. While `isPubkyProfileSetupPending` is set (adopted identity without a profile) and the user is authenticated, `PubkyProfileSetupNavigation` in `ContentView.kt` opens `CreateProfile` once.
- If the adopted Ring identity disappears from Ring, `AppViewModel` shows the `profile__source_lost` toast and navigates to `PubkyChoice`.
- Approval sheet: a `pubkyauth://` URL from the home scanner (not Send manual entry or other scanners) or a deeplink; `pubkyring://signup` opens a signup request. Android activity aliases `.ui.MainActivityPubkyAuth` (`signin_grant`, `signup_grant`) and `.ui.MainActivityPubkySignup` (`signup`, `direct_signup`, `pubkyring://signup`) are switched on by `services/PubkyAuthHandlerRegistrar.kt` depending on identity state. Handling order is in `deeplinks.md`.
- Routes `Profile`, `ProfileIntro`, `PubkyChoice`, `CreateProfile`, `EditProfile`, `PayContacts` are in the `profile(...)` builder of `ContentView.kt` behind `PaykitRouteGuard`.
- Paykit UI gate: build flag `PAYKIT_UI_DISABLED` (`app/build.gradle.kts`, `flags/PaykitFeatureFlags.kt`) and a local setting, default on, toggled by "Enable Paykit UI" in `ui/screens/settings/DevSettingsScreen.kt` (dev settings). When off, `Profile` shows `ComingSoonScreen` and the other routes redirect home; scanned `pubkyauth://` shows an error toast.

## Code
- `app/src/main/java/to/bitkit/ui/screens/profile/ProfileIntroScreen.kt`: intro.
- `app/src/main/java/to/bitkit/ui/screens/profile/PubkyChoiceScreen.kt` + `PubkyChoiceViewModel.kt`: Ring identity rows, adoption, create option.
- `app/src/main/java/to/bitkit/ui/screens/profile/CreateProfileScreen.kt` + `CreateProfileViewModel.kt`: create or restore form.
- `app/src/main/java/to/bitkit/ui/screens/profile/PayContactsScreen.kt` + `PayContactsViewModel.kt`: enables contact payments through `repositories/ContactPaymentSettingsRepo.kt`.
- `app/src/main/java/to/bitkit/ui/screens/profile/ProfileScreen.kt` + `ProfileViewModel.kt`: display, cached header, tags, copy, share, disconnect.
- `app/src/main/java/to/bitkit/ui/screens/profile/EditProfileScreen.kt` + `EditProfileViewModel.kt`: edit, delete with retry or disconnect (shared form `ui/components/ProfileEditForm.kt`, `AddLinkSheet.kt`, `AddTagSheet.kt`).
- `app/src/main/java/to/bitkit/ui/screens/profile/PubkyAuthApprovalSheet.kt` + `PubkyAuthApprovalViewModel.kt`: approval sheet and flow; `ui/utils/PubkyAuthErrorMessage.kt` maps errors.
- `app/src/main/java/to/bitkit/repositories/PubkyRepo.kt`: session lifecycle, `adoptRingIdentity`, `createIdentity`, `loadProfile`, `saveProfile`, `deleteProfile`, `signOut`, `parseAuthUrl`, `approveAuth`, `approveAuthWithCompanionClaim`, `approveSignupAuth`.
- `app/src/main/java/to/bitkit/services/PubkyService.kt` and `PaykitSdkService.kt`: wrappers over `com.synonym:paykit-android`.
- `app/src/main/java/to/bitkit/data/sharedpubky/SharedPubkyClient.kt`, `SharedPubkyContract.kt`, `SharedPubkyProvider.kt`: reads Ring's provider (permission `to.pubky.ring.permission.READ_SHARED_PUBKY`) and exposes Bitkit's own provider.
- `app/src/main/java/to/bitkit/data/PubkyStore.kt`: cached name, avatar URI and owner key. `data/PubkyImageFetcher.kt`, `PubkyImageCacheEpoch.kt`, `ui/components/PubkyImage.kt`: avatar loading and caches.
- `app/src/main/java/to/bitkit/models/PubkyAuthRequest.kt`, `PubkyAuthClaimCodec.kt`, `PubkyProfile.kt`: request parsing, claim payloads, profile model.
- `app/src/main/java/to/bitkit/repositories/WatchOnlyAccountRepo.kt`: watch-only account for a companion claim (screen in `settings-advanced.md`).
- Docs: `docs/pubky.md`, `docs/pubky-auth-companion-claims.md`. Unit tests: `app/src/test/java/to/bitkit/ui/screens/profile/`.

## How to drive it
- Journeys in `journeys/profile/` (`journeys/profile/README.md`):
  - `delete-profile.xml`: Home header > Edit Profile > Delete Profile > confirm; progress shown, onboarding after, profile does not reappear. Disposable identity only.
- Journeys in `journeys/pubky-profile/` (`journeys/pubky-profile/README.md`; Ring setup and emulator throttle there):
  - `cached-profile-header.xml`: force-stop, relaunch, Profile shows `ProfileCachedHeader` read-only, then the full profile.
  - `ring-choice-rows.xml`: Ring rows with lookup spinners, then adoption disables all rows. Needs Ring signed with `app/debug.keystore` and two identities.
  - `contact-import-after-leaving.xml`, `contacts-list-loading.xml`: see `contacts.md`.
- `journeys/pubky-marketplace/` exercises the approval sheet (`paykit-only-approval.xml`, `paykit-reconnect.xml`, `wallet-leg.xml`): see `payment-requests.md`.
- E2E, `bitkit-e2e-tests/test/specs/pubky-profile.e2e.ts`, describe tags `@pubky @pubky_profile @pubky_staging, @staging`:
  - `@pubky_profile_1`: `ProfileButton` > `ProfileIntro` > `PubkyChoiceCreate`; drawer Contacts and Profile reach the same screen.
  - `@pubky_profile_2`: create, copy pubky, edit name, bio, link, tag, add contact, relaunch, backup and restore wallet (same pubky), remove link and tag, delete profile, recreate (same pubky).
  - `@pubky_profile_3`, `@pubky_profile_4`: contacts, see `contacts.md`.
  - `@pubky_profile_5`: after creating a profile, scan prompt with `pubkyauth://direct_signup?...` shows "Already signed in" and neither `CreateProfileUsername` nor `PubkyAuthAuthorize`.
- CI: all run in the `pubky_paykit` shard (`@pubky_staging`) of `.github/workflows/e2e-staging.yml` (nightly and manual dispatch, `BACKEND=regtest`, app built for the network backend). Not in local-backend shards of `e2e.yml`. `bitkit-e2e-tests/AGENTS.md` lists the tag table.
- Helpers: `bitkit-e2e-tests/test/helpers/profile.ts` (`createProfile`, `openEditProfile`, `updateMyProfile`, `deleteProfile`, `verifyMyProfileDetails`). Avatar fixtures: `./scripts/push-fixture-media-to-devices.sh` in the e2e repo.
- Manual charters: `bitkit-e2e-tests/docs/pubky-profile-manual-e2e.md` (sections A to H), `bitkit-e2e-tests/docs/public-contact-payments-manual-qa.md` (S1, S2, T7, T8: endpoint publishing and lifecycle).
- Preconditions: Homegate and staging Pubky reachable (`Env.homegateUrl` in `env/Env.kt`: staging off mainnet, `E2E_HOMEGATE_URL` for local e2e), PIN off for the journeys, no funds needed. A QA wallet with a profile: `BACKEND=regtest ./scripts/qa-fixture.sh android pubky` in the e2e repo.

## What proves it
- Created profile: `ProfileEdit`, `ProfileCopy`, `ProfileShare` visible, `ProfileViewName` shows the name in capitals, `ProfileQRCode` decodes to a key starting `pubky`; `PayContactsContinue` shown once before.
- Edit: toast `ProfileUpdatedToast`, then `ProfileEdit`; `ProfileViewNotes`, `ProfileLinkLabel_<n>`, `ProfileLinkValue_<n>`, `Tag-<tag>` match.
- Copy: toast `ProfilePubkyCopiedToast`, clipboard holds the key.
- Delete: `PubkyChoiceCreate` visible, recreated profile has the same pubky (seed-derived).
- Cached load: `ProfileCachedHeader` and `ProfileCachedName`, no `ProfileEdit`, `ProfileCopy`, `ProfileShare`, `ProfileQRCode`, `ProfileAddTag`; then `ProfileViewName`.
- Approval: `PubkyAuthAuthorize` then `PubkyAuthOK`.

## Not covered by tests
- Avatar pick and remove in create and edit (`CreateProfileAvatar`, `EditProfileAvatar`), size limits.
- Profile `ProfileShare`, adding or removing tags from the Profile screen (`ProfileAddTag`); e2e adds tags only in the edit form.
- Restore title and prefill in `CreateProfile` for a key with a remote profile (only the same-pubky check after delete and recreate).
- Retry state (`ProfileRetry`) and Disconnect (`ProfileEmptySignOut`); delete failure dialog (Retry, Disconnect); needs network fault injection (`journeys/profile/README.md`).
- Adopting a Ring identity with no profile (leads to `CreateProfile`), a failed adoption, Ring identity removed (`profile__source_lost`).
- Pay Contacts failure toasts.
- Session restore failures after cold start and background resume (manual, `journeys/profile/README.md`).
- Approval sheet with a live relay: sign-in and signup approval, PIN or biometric prompt before Authorize, error mapping. Only `@pubky_profile_5` (rejects signup) and the marketplace consent and cancel journeys run it.
- Dev settings "Enable Paykit UI" and the disabled-UI behavior; `PAYKIT_UI_DISABLED` build.
- Mainnet Homegate.

## Gotchas
- Android has no `PayContactsToggle`; the Pay Contacts screen only enables sharing on Continue. `createProfile({ payContactsOption: false })` in `test/helpers/profile.ts` would fail on Android; no spec uses it. The manual charter's "toggle / Skip" does not apply.
- Profile name shows in capitals; compare case-insensitively. Link labels too.
- Every Ring row has the tag `PubkyChoiceIdentity`; tell rows apart by key caption. The avatar has no text; check it by screenshot.
- Opening Profile right after a relaunch can show `ProfileLoading` before `ProfileCachedHeader`.
- The cached header shows only when `cachedProfileOwner` matches the current key (`docs/pubky.md`).
- Ring identity visibility needs Ring signed with the same key as the Bitkit build and the manifest `<queries>` package `to.pubky.ring`; otherwise only `PubkyChoiceCreate` appears.
- Pubky keys derive from the wallet seed: wipe and restore of the same seed gives the same pubky (`pubky-profile-manual-e2e.md`).
- `pubkyauth://` is ignored by Send manual entry and non-main scanners; use the home scanner, scan prompt or `adb shell am start -a android.intent.action.VIEW -d "<url>"`. Signup links with an existing identity show "Already signed in".
- The `x-bitkit-claim` list order is signed and relayed unchanged; do not reorder (`docs/pubky-auth-companion-claims.md`).
- Delete Profile removes remote profile data only; Disconnect forgets the local identity only.
- Tests that reinstall create a new wallet and leave staging profiles behind by design (`profile.ts` comment on `deleteProfile`).
- The charter says Notes; Android labels the field "Bio" (`ProfileEditBio`).
