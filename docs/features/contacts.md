# Contacts

Saved Pubky contacts: list, search, add by key or QR, detail, edit, delete, per-contact activity, import from Pubky Ring, and the setting that shares the user's payment data with contacts.

## What it does
- Contacts are Paykit contact records of the active Pubky identity. They need a Pubky identity (see `profile.md`); without one the drawer leads to the profile onboarding.
- List (`ContactsScreen`): search field, add button, "my profile" row, one flat `CONTACTS` header, contacts sorted by name (case-insensitive). Rows appear at once from saved records; names and avatars fill in as background profile lookups finish (details in `docs/pubky.md`, "Contacts").
- Add: a sheet takes a pasted key or a scanned QR and validates it (empty, invalid format, own key, already saved). A valid key opens `AddContactScreen`, which fetches the profile, shows Save and, when the contact has a public payment endpoint, Pay. Save opens the contact detail with delete action.
- Detail (`ContactDetailScreen`): name, bio, links, tags, and buttons Pay, Activity, Copy, Share, Edit. Pay opens a Request-or-Pay sheet when a payment request target exists, otherwise starts a payment (flow in `send.md` and `payment-requests.md`).
- Edit (`EditContactScreen`): name, bio, links, tags, delete. Edits are local overrides; the contact's own Pubky profile is not changed (`contacts__edit_public_note`).
- Delete is refused with a toast while the contact has an active subscription (`contacts__delete_active_subscription`).
- Contact activity (`ContactActivityScreen`): activities tagged with the contact.
- Import from Pubky Ring: after a Ring identity with a profile is adopted, the import overview lists the identity's follows. Import All saves all; Select opens a checklist. The own key is excluded. Both end on Pay Contacts (`profile.md`).
- Contact payment sharing: Settings > General toggle `ContactPaymentsToggle` (shown when Paykit UI is on and a Pubky identity exists) publishes or withdraws the user's public and private payment endpoints. `PayContactsScreen` turns it on for the first time.

## How a user reaches it
- Drawer: `HeaderMenu` > `DrawerContacts`. Logic in `app/src/main/java/to/bitkit/ui/components/DrawerMenu.kt`: Paykit UI off opens `Routes.Contacts`; intro not seen and no contacts opens `Routes.ContactsIntro`; identity present opens `Routes.Contacts`; otherwise profile onboarding (`ProfileIntro` or `PubkyChoice`, or `Profile` if an identity exists).
- Intro: `ContactsIntro`, button `ContactsIntro-button` opens `Routes.Contacts(showAddContactSheet = true)` when authenticated, else the profile flow.
- Add: `ContactsAddButton`, or `ContactsEmptyAddButton` (empty list), then `AddContactPubkyField` (`AddContactPaste`, `AddContactScanQR`), `AddContactAdd`, then `AddContactSave` on `Routes.AddContact`.
- Detail: row `Contact_<publicKey>` (full key with `pubky` prefix) > `Routes.ContactDetail`; `ContactEdit` > `Routes.EditContact`; `ContactActivity` > `Routes.ContactActivity`.
- Import: `PubkyChoiceIdentity` row (Ring identity) > `Routes.ContactImportOverview` (`ContactImportOverviewImportAll`, `ContactImportOverviewSelect`) > `Routes.ContactImportSelect` (`ContactImportSelectContinue`) > `Routes.PayContacts` (`PayContactsContinue`).
- Pubky keys also route here from Send > manual entry, the scan prompt (paste), the Contacts scanner and `bitkit://contact?pubky=<key>`: own key opens `Profile`, saved key `ContactDetail`, other key `AddContact` (`resolvePastedPubkyRoute` in `viewmodels/AppViewModel.kt`). Deeplink details belong to `deeplinks.md`.
- Settings toggle: Settings > General tab (`Tab-general`) > `ContactPaymentsToggle`.
- Routes are in `app/src/main/java/to/bitkit/ui/ContentView.kt` (`contacts(...)` graph builder). `Routes.Contacts` shows `ComingSoonScreen` when Paykit UI is disabled; the other routes redirect home.

## Code
- `app/src/main/java/to/bitkit/ui/screens/contacts/ContactsIntroScreen.kt`: intro, sets `hasSeenContactsIntro`.
- `app/src/main/java/to/bitkit/ui/screens/contacts/ContactsScreen.kt` + `ContactsViewModel.kt`: list, search, add sheet host; reloads via `PubkyRepo.loadContacts()` on open.
- `app/src/main/java/to/bitkit/ui/screens/contacts/AddContactScreen.kt` + `AddContactViewModel.kt` + `ContactImportFlow.kt`: add sheet (`AddContactSheet`), full add screen, key validation (`resolveAddContactValidation`), pay-before-save.
- `app/src/main/java/to/bitkit/ui/screens/contacts/ContactDetailScreen.kt` + `ContactDetailViewModel.kt`: detail, tags, copy, share, delete, Pay and Request-or-Pay sheet.
- `app/src/main/java/to/bitkit/ui/screens/contacts/EditContactScreen.kt` + `EditContactViewModel.kt`: edit form (shared `ui/components/ProfileEditForm.kt`) and delete.
- `app/src/main/java/to/bitkit/ui/screens/contacts/ContactActivityScreen.kt` + `ContactActivityViewModel.kt`: activity list via `ActivityRepo.contactActivities`; row prefix `ContactActivity`.
- `app/src/main/java/to/bitkit/ui/screens/contacts/ContactImportOverviewScreen.kt` + `ContactImportOverviewViewModel.kt`, `ContactImportSelectScreen.kt` + `ContactImportSelectViewModel.kt`: import UI. `shouldDiscardPendingImport` drops a pending import when the user leaves.
- `app/src/main/java/to/bitkit/repositories/PubkyRepo.kt`: `loadContacts`, `fetchContactProfile`, `addContact`, `updateContact`, `removeContact`, `prepareImport`, `importContacts`, `discardPendingImport`.
- `app/src/main/java/to/bitkit/repositories/ContactPaymentSettingsRepo.kt`: sharing on/off; uses `PublicPaykitRepo.kt` and `PrivatePaykitRepo.kt`.
- `app/src/main/java/to/bitkit/usecases/RefreshContactPaykitLinkUseCase.kt`: refreshes a saved contact's Paykit link.
- `app/src/main/java/to/bitkit/models/PubkyProfile.kt`, `PubkyPublicKeyFormat.kt`, `PubkyContactLink.kt`: model, key normalization, `bitkit://contact` parsing.
- `app/src/main/java/to/bitkit/ui/components/PubkyContactRow.kt`, `PubkyContactAvatar.kt`, `CenteredProfileHeader.kt`: row and header.
- Screens outside this scope that use contacts: `ActivityAssignContactScreen.kt` (`activity.md`), `SendContactSelectScreen.kt` (`send.md`).
- Background reads, caching, import rules: `docs/pubky.md`. Unit tests: `app/src/test/java/to/bitkit/ui/screens/contacts/`.

## How to drive it
- Journeys in `journeys/contacts/` (need an authenticated disposable Pubky identity, see `journeys/contacts/README.md`):
  - `delete-newly-saved-contact.xml`: add by key, delete from Contact Saved, check Back history, add again.
  - `import-all-contacts.xml`: Import All from the preview, own-key exclusion, 62-contact list, Pay Contacts, Contacts list.
  - `import-selected-contacts.xml`: Select, deselect one, import, check the list.
  - `contact-payment-sharing.xml`: turn `ContactPaymentsToggle` off and check it stays off after leaving Settings.
- Journeys in `journeys/pubky-profile/` (setup, emulator throttle and Ring signing in `journeys/pubky-profile/README.md`):
  - `contacts-list-loading.xml`: relaunch, rows at once, names fill in, contact bio and edit form.
  - `contact-import-after-leaving.xml`: Import All then Back; import continues, Pay Contacts does not open.
- Import journeys need Pubky Ring (`to.pubky.ring`) installed and signed with `app/debug.keystore`, and a Ring identity that follows pubkys (62 for the large case).
- E2E, `bitkit-e2e-tests/test/specs/pubky-profile.e2e.ts` (tags in `profile.md`):
  - `@pubky_profile_1`: Contacts entry leads to `ContactsIntro` then `PubkyChoice` without a profile.
  - `@pubky_profile_2`: add a staging contact, contact row persists after relaunch and restore.
  - `@pubky_profile_3`: invalid key and own key blocked (`AddContactAdd` disabled), routes from Send manual entry and scan prompt, add and delete two contacts.
  - `@pubky_profile_4`: edit a contact on wallet B; wallet A profile unchanged.
- `bitkit-e2e-tests/test/specs/paykit.e2e.ts` (`@paykit_1`, `@pubky @paykit @pubky_staging`) pays and views activity of a saved contact: see `payment-requests.md`.
- CI: `pubky-profile.e2e.ts` and `paykit.e2e.ts` run in the `pubky_paykit` shard (`@pubky_staging`) of `.github/workflows/e2e-staging.yml` (nightly 04:00 and manual dispatch, `BACKEND=regtest`). They are not in the local-backend shards of `e2e.yml`.
- Helpers: `bitkit-e2e-tests/test/helpers/profile.ts` (`addContact`, `deleteContact`, `updateContactProfile`, `verifyContactRowDisplayed`), fixtures in `test/helpers/fixtures.ts` (`STAGING_TEST_CONTACTS`, `STAGING_PAYKIT_CONTACTS`).
- Manual charters in `bitkit-e2e-tests/docs/pubky-profile-manual-e2e.md` (sections B.4 to B.7) and `bitkit-e2e-tests/docs/public-contact-payments-manual-qa.md` (payments to contacts, endpoint lifecycle).
- Preconditions: staging Pubky (Homegate) reachable for profile creation, no funds for contact management; paying a contact needs funds (`send.md`).

## What proves it
- Add: `ContactViewName`, `ContactPay`, `ContactActivity`, `ContactCopy`, `ContactShare`, `ContactDelete`, `ContactAddTag` visible after `AddContactSave`; row `Contact_<publicKey>` in the list.
- Edit: toast `ContactUpdatedToast`, then `ContactEdit` visible; `ContactViewName` and `ContactViewNotes` show the new values.
- Delete: toast `ContactDeletedToast`, Contacts list without that `Contact_<publicKey>` row, `ContactsAddButton` visible.
- Import: Pay Contacts (`PayContactsContinue`) after Import All; one `Contact_<pubky>` row per counted friend; own profile absent from the list.
- Sharing off: `ContactPaymentsToggle` off after reopening Settings.

## Not covered by tests
- Contacts search field, tapping the "my profile" row (`ContactsMyProfile`), the empty state copy.
- Duplicate add: the e2e case is commented out in `pubky-profile.e2e.ts`; `already in your contacts` is not asserted.
- Scan QR from the add sheet, paste with a saved key (refreshes its Paykit link), `AddContactPay` on an unsaved contact (only checked as visible or hidden in `@paykit_1` and `@pubky_profile_3`).
- Contact tags (`ContactAddTag`, remove), `ContactCopy` content, `ContactShare`, the Request-or-Pay sheet (`RequestOrPaySheet`).
- Contact activity beyond `@paykit_1`; `ContactActivityRetry`.
- Delete refused by an active subscription; delete failure.
- Import select screen details (select all, none), a failed import with retry, own-key padding alias, Back on the overview with a running import (fault injection is manual per `journeys/contacts/README.md`).
- Enabling `ContactPaymentsToggle` again, and cleanup retries after a failed withdrawal (manual fault injection only).
- Paykit UI disabled (`ComingSoonScreen`).

## Gotchas

- On a fresh wallet the Wallet Backup prompt can open over Contact Detail; dismiss it before driving a contact.
- The import journeys need a Pubky identity that already follows the contacts to import; on a fresh wallet Contacts shows its onboarding, not an import preview.
- After `AddContactSave` the detail shows `ContactDelete` instead of `ContactEdit`; after reopening from the list it shows `ContactEdit` (`showDeleteAction` in `ContactDetailScreen.kt`). E2E `deleteContact` goes through Edit.
- Android has no `AddContactRetrievingTitle`, `AddContactDiscard` or `ContactSavedToast` testTag; `verifyAddContactRoute` accepts any of three states, so it passes on `AddContactSave` or `AddContactPay`.
- `docs/pubky.md` says the list is alphabetically grouped; the code shows one `CONTACTS` header over a name-sorted list.
- Just after a relaunch, while the Pubky session restores, the drawer item can open the intro or the profile instead of Contacts. Return home and open it again (`journeys/pubky-profile/README.md`).
- The edit form calls the notes field "Bio" (`ProfileEditBio`); the e2e charter says NOTES.
- The import overview has no testTag except `ContactImportOverviewImportAll` and `ContactImportOverviewSelect`; read the "Import" title and "N friends" text.
- Import saves only on the device. Private link preparation runs afterwards in the background and is retried after five minutes (`journeys/contacts/README.md`).
- A follow whose profile lookup takes over ten seconds is imported under its truncated key.
- Journeys that load profiles need the emulator network slowed to see loading states (`adb emu network delay gprs`, `adb emu network speed edge`).
- Removing a contact blocks its receiver paths; deleting during background preparation must not republish it (`docs/pubky.md`).
