# Pubky profile journeys

These journeys cover the states a Pubky profile passes through while it loads: the Profile screen
opened before the profile has loaded after a relaunch, and the Pubky Ring choice screen while its
rows look up their profiles and while one of them is adopted. `bitkit-ios` carries the same suite
with the same file and journey names.

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

## Setup

1. **Slow the network on an emulator.** On a fast network the profile load, the row lookups and
   the adoption can all finish before the screen is inspected. Both journeys start with
   `adb emu network delay gprs` and `adb emu network speed edge` and end with
   `adb emu network delay none` and `adb emu network speed full`. `adb emu` works only on an
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

## Gotchas

- **The session can restore after the profile button is tapped.** The button then opens the Pubky
  choice screen, which moves on to Profile by itself once the session is back.
- **Every choice row shares one test tag.** Tell the rows apart by their key caption. iOS gives each
  row its own identifier; see the identifier table in `journeys/README.md`.
- **The avatar has no text.** Check it from a screenshot rather than `android layout`.

## Test tags used

- Home: `ProfileButton`.
- Profile, cached: `ProfileCachedHeader`, `ProfileCachedName`.
- Profile, loaded: `ProfileViewName`, `ProfileEdit`, `ProfileQRCode`, `ProfileShare`,
  `ProfileViewTagsHeader`; failed load `ProfileRetry`.
- Profile intro: `ProfileIntro`, `ProfileIntro-button`.
- Pubky choice: `PubkyChoiceIdentity` for each Ring row, `PubkyChoiceIdentityLookup` for a row's
  lookup spinner and `PubkyContactAvatar` for the avatar that replaces it, `PubkyChoiceCreate` when
  Ring has none.
