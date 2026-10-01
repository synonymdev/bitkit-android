# Pubky profile journeys

These journeys cover the states a Pubky profile passes through while it loads: the Profile screen
opened before the profile has loaded after a relaunch, the Pubky Ring choice screen while its
rows look up their profiles and while one of them is adopted, and Contacts opened after a relaunch
while its profiles load. They also cover a contact import that finishes after you leave it.
Importing contacts itself is covered by `journeys/contacts`, which arrives with
synonymdev/bitkit-android#1395. `bitkit-ios` carries the same suite with the same file names and
journey names, and the steps differ only where the platform forces it; see
[Android vs iOS](#android-vs-ios).

## What the behaviour is

- **Profile shows a cached header while it loads.** `PubkyStore` keeps the last loaded name, avatar
  URI and the public key they belong to. While a load is in flight for that same key,
  `ProfileScreen` shows a read-only header with the cached name, avatar and truncated key and an
  inline spinner (`ProfileCachedHeader`, `ProfileCachedName`). Edit, Copy, the QR code, Share and
  Tags appear only with the loaded profile. A failed load still ends on the retry state
  (`ProfileRetry`). The avatar comes from the Pubky image disk cache, so it shows without the
  network.
- **Ring rows show at once.** The choice screen lists Ring's identities as soon as the shared pubky
  provider answers. Each row is captioned and titled with its truncated key, and a row's title
  becomes the profile name when that row's lookup finishes, rather than the screen waiting for all
  of them. While a row's lookup runs, a spinner (`PubkyChoiceIdentityLookup`) stands in for its
  avatar, so a row still looking up does not look like a row whose identity has no profile.
- **Adoption marks one row.** Tapping a row shows a spinner in place of that row's key icon only,
  keeps its avatar, and disables every row until the adoption succeeds or fails. On Android the
  other rows' lookups keep running during an adoption, so a name can still fill in on a disabled
  row; the journey does not assert on that either way.
- **The import keeps every follow.** The import overview counts the follows the adopted identity
  publishes. Import All saves each one with the profile the overview already looked up, and a follow
  whose profile could not be looked up, such as a key that never published one, is saved under its
  truncated public key rather than dropped. While the import runs, Import All shows a spinner and
  both buttons are disabled. The import belongs to the app rather than the screen, so leaving the
  overview does not stop it, and an import that finishes after you left does not take you to Pay
  Contacts. `contact-import-after-leaving.xml` checks the leaving; the saving is covered by
  `journeys/contacts` once synonymdev/bitkit-android#1395 merges.
- **Contacts lists saved contacts at once.** Contacts shows every saved contact as soon as the saved
  records are read, under its saved name or truncated key, and fills in a name and avatar when that
  contact's profile lookup finishes; a lookup that fails leaves the row as it is. The screen-wide
  spinner shows only until the saved records first load. Reopening Contacts in the same session
  shows the profiles already found at once. Opening a contact whose profile has not loaded yet looks
  it up at once, and its edit form shows the published bio.

## Setup

1. **Slow the network on an emulator.** On a fast network the profile load, the row lookups, the
   adoption, the import and the contact lookups can all finish before the screen is inspected. The
   journeys keep the iOS steps, which report "already loaded", "already resolved", "already
   adopted" or "already imported" instead of failing, so slow the network with
   `adb emu network delay gprs` and `adb emu network speed edge` before a journey's first step and
   restore it with `adb emu network delay none` and `adb emu network speed full` after its last to
   catch the loading states. A contact import here still reads each resolved follow's Paykit
   receiver paths from the network, so the throttle also keeps it running long enough to leave.
   synonymdev/bitkit-android#1395 makes it save only to the device, as on iOS; it can then finish
   before you leave it, and the journey reports "already imported". `adb emu` works only on an
   emulator. If a state still passes too quickly, use `adb emu network speed gsm`; if loads start
   failing, relax the throttle.
2. **Cached header:** an active Pubky profile with a name and an avatar, PIN off, and Profile
   opened once on this install so the cache is filled. The journey does that as its first steps.
3. **Ring rows (not a listed capability):** Pubky Ring (`app.pubkyring`) installed on the same
   device with at least two identities, at least one with a published profile name, and no active
   Pubky identity in Bitkit. Bitkit reads Ring's shared pubky provider only when both apps are
   signed with the same key, so a dev build needs a Ring build signed with `app/debug.keystore`.
   Without that, the choice screen offers only "Create profile with Bitkit" (`PubkyChoiceCreate`).
   The journey adopts one identity; sign out of it in Bitkit before running the journey again.
4. **Contact import after leaving (not a listed capability):** the same Ring setup, with an
   identity that has a published profile and follows many pubkys on pubky.app (62, as in
   `journeys/contacts/import-all-contacts.xml`, which arrives with synonymdev/bitkit-android#1395),
   so the import takes long enough to leave. The journey saves the follows as contacts; sign out in
   Bitkit before running it again.
5. **Contacts list loading:** a Pubky identity with at least five saved contacts, at least one with
   a published profile name and bio and one with no published profile. Importing such follows with
   `journeys/contacts/import-all-contacts.xml` leaves exactly that.

## Gotchas

- **The session can restore after the profile button is tapped.** The button then opens the Pubky
  choice screen, which moves on to Profile by itself once the session is back.
- **Every choice row shares one test tag.** Tell the rows apart by their key caption. iOS gives each
  row its own identifier; see the identifier table in `journeys/README.md`.
- **The avatar has no text.** Check it from a screenshot rather than `android layout`.
- **Contacts can open the wrong screen right after a relaunch.** While the session is still
  restoring, the menu's Contacts item opens the contacts intro or the Pubky choice screen instead,
  and the choice screen moves on to Profile once the session is back. Return to the home screen and
  open Contacts again.
- **The edit form calls the notes field "Bio".** `contacts-list-loading.xml` keeps the iOS wording;
  the field carries `ProfileEditBio`.

## Android vs iOS

All four journeys share their file names and journey names with `bitkit-ios`. Every step names
identifiers as testTags rather than iOS ids, drops the iOS `predicate exists` wait argument and runs
`adb` instead of `xcrun simctl`; the differences below are the rest.

- **Cached profile header while loading.** Two steps differ. While the session is still restoring,
  the profile button opens the Pubky choice screen, which moves on to Profile by itself, so that
  step waits for the choice screen where iOS waits for a bare initialization spinner. Android also
  adds a screenshot check that the cached header shows the profile's avatar: the avatar comes from
  the Pubky image disk cache that synonymdev/bitkit-android#1399 adds, so the check is what shows
  it works without the network. iOS shows the cached avatar too but does not check it.
- **Pubky ring choice rows.** Every row is `PubkyChoiceIdentity` and every lookup spinner
  `PubkyChoiceIdentityLookup`, where iOS gives each its own `PubkyChoiceRing_<pubky>` and
  `PubkyChoiceRingLookup_<pubky>`, so the steps tell rows apart by their key caption. The row
  avatar has a testTag here (`PubkyContactAvatar`) and none on iOS. Three steps differ: the profile
  button always opens the next screen, so it needs no second tap; a disabled row ignores taps, so
  the disabled check taps another row and expects nothing to change, where iOS reads the rows
  missing from the snapshot's Targets list; and the import overview has no testTag, so the last
  step names its "Import" title. On Android the other rows' lookups keep running during an
  adoption, where iOS stops them; the journey checks the rows only after every lookup has finished,
  so it does not depend on that.
- **Contact import after leaving.** `contact-import-after-leaving.xml` has the same file, journey
  name and steps on both platforms, and the steps differ only in identifiers. Android tags every
  Ring row `PubkyChoiceIdentity`, where iOS tags each `PubkyChoiceRing_<pubky>`, and the import
  overview's profile and friend count have no testTag here (iOS: `ContactImportOverviewProfile`,
  `ContactImportOverviewSummary`), so the journey names the "Import" title and the "N friends" text
  instead. synonymdev/bitkit-android#1399 adds `ContactImportOverviewImportAll`, the only contact
  import testTag the journeys use; Back is the shared `NavigationBack`. See the Identifiers table
  in [`journeys/README.md`](../README.md#identifiers). Until synonymdev/bitkit-android#1395 merges,
  the import also takes longer here: iOS saves only to the device, while Android still reads each
  resolved follow's Paykit receiver paths from the network, so the import can still be running
  when the journey opens Contacts. The app log shows "Imported 'N' contacts" when it finishes.
- **Contacts list loading.** `contacts-list-loading.xml` has the same file, journey name and steps
  on both platforms; only the relaunch commands differ.

## Test tags used

- Home: `ProfileButton`.
- Profile, cached: `ProfileCachedHeader`, `ProfileCachedName`.
- Profile, loaded: `ProfileViewName`, `ProfileEdit`, `ProfileCopy`, `ProfileShare`, `ProfileQRCode`,
  `ProfileAddTag`; failed load `ProfileRetry`.
- Profile intro: `ProfileIntro`, `ProfileIntro-button`.
- Pubky choice: `PubkyChoiceIdentity` for each Ring row, `PubkyChoiceIdentityLookup` for a row's
  lookup spinner and `PubkyContactAvatar` for the avatar that replaces it, `PubkyChoiceCreate` when
  Ring has none.
- Contact import: `ContactImportOverviewImportAll` and Back `NavigationBack`; Pay Contacts:
  `PayContactsContinue`.
- Contacts: `HeaderMenu`, `DrawerContacts`, the contacts intro's `ContactsIntro-button`,
  `Contact_<pubky>` for each row (the full key with its `pubky` prefix); contact screen
  `ContactViewName`, `ContactViewNotes`, `ContactEdit`, `NavigationBack`; edit form
  `ProfileEditBio`, `ProfileEditCancel`.
