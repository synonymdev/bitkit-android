# Shop

Bitrefill gift cards, eSIMs, phone refill and travel in an embedded web view, plus a BTC Map tab, reached from the drawer or the Shop suggestion card.

## What it does
- First visit shows `ShopIntroScreen` (title, description, `Continue`). `Continue` sets `hasSeenShopIntro` and opens Shop Discover; later visits go straight to Discover.
- `ShopDiscoverScreen` has two tabs, `Tab-shop` and `Tab-map` (tag from enum name, `CustomTabRowWithSpacing.kt`).
  - Shop tab: four cards (Gift Cards `gift-cards`, eSIMs `esims`, Refill `refill`, Travel `buy/travel`) and a "GIFT CARD CATEGORIES" list of 22 rows from `models/BitrefillCategory.kt` (Apparel ... VoIP), each with a Bitrefill path such as `buy/food-delivery`.
  - Map tab: a `WebView` on `Env.BTC_MAP_URL` (`https://btcmap.org/map`) with a loading spinner.
- Tapping a card or row opens `ShopWebViewScreen` with top bar "Shop <title>" and loads `Env.BITREFILL_URL/<page>/?ref=...&paymentMethod=bitcoin&theme=dark&utm_source=Bitkit`. The card/row title is passed through as the top-bar title.
- Payment: Bitrefill posts a `payment_intent` message with a `paymentUri`; `ShopWebViewInterface` accepts it only from the trusted origin (`ShopOrigin.kt`) and the app handles it like a scanned payment (`appViewModel.onScanResult(data, allowPubkyAuth = false)`), which runs the normal scan handling (`send.md`). If the device WebView has no `WEB_MESSAGE_LISTENER`, the bridge is disabled and a warning is logged.
- Main-frame navigation to hosts other than `bitrefill.com` and its subdomains (https only) is blocked with a warning toast "This link can’t be opened from the shop." (`ShopWebViewClient.shouldOverrideUrlLoading`).
- Main-frame load errors (host lookup, connect, timeout, file not found) close the screen and return Home (`onError = onClose`, `navigateToHome`).
- Back button goes back in the web view history first, then pops the screen.

## How a user reaches it
- `HeaderMenu` -> `DrawerShop` (intro on first use, `Routes.ShopIntro`; later `Routes.ShopDiscover`).
- Home widgets page -> `Suggestion-shop` card (only listed when lightning balance > 0, see `home.md`).
- Then a card or category row -> `Routes.ShopWebView(page, title)`.
- Deeplinks (debug build, dev mode on): `bitkit://screen/shop-intro`, `bitkit://screen/shop-discover`, `bitkit://screen/shop-web-view` (`Routes.ShopWebView` has `page` and `title` arguments; argument encoding in the link not verified, no journey uses it). The Discover link skips the intro without marking it seen.

## Code
- `app/src/main/java/to/bitkit/ui/screens/shop/ShopIntroScreen.kt`: intro.
- `ui/screens/shop/shopDiscover/ShopDiscoverScreen.kt`: tabs, cards, category list, map `WebView`; `MapWebViewClient.kt`: map loading state.
- `ui/screens/shop/shopWebView/ShopWebViewScreen.kt`: Bitrefill URL and params, back handling.
- `ShopWebViewClient.kt`: loading state, blocked navigation, error close, bridge script injection. `ShopWebViewInterface.kt` and `WebViewMessage.kt`: `payment_intent` handling. `ShopOrigin.kt`: allowed hosts and payment origin rules.
- `models/BitrefillCategory.kt`: category titles (`other__shop__categories__*` strings), paths and icons.
- `ui/components/SuggestionCard.kt`: card used by the Shop tab. `ui/ContentView.kt` (`ShopIntro`, `ShopDiscover`, `ShopWebView` routes), `ui/components/DrawerMenu.kt`, `ui/screens/wallets/HomeScreen.kt` (Shop suggestion routing), `viewmodels/SettingsViewModel.kt` (`hasSeenShopIntro`).
- `Env.kt`: `BITREFILL_URL`, `BITREFILL_REF`, `BITREFILL_APP`, `BTC_MAP_URL`.

## How to drive it
- `journeys/shop/gift-card-category-titles.xml`: opens `bitkit://screen/shop-discover`, checks `Tab-shop`/`Tab-map` and the 22 rows in order, opens "Food Delivery" (top bar "Shop Food Delivery", Bitrefill listing by screenshot), then switches app locale to `de` and checks the screen and rows again. Needs a debug build, dev mode, network to Bitrefill. German strings are not shipped yet, so rows stay English.
- No Appium spec covers Shop (`grep -i shop|bitrefill` in `bitkit-e2e-tests/test` finds nothing relevant).
- Unit tests only for the web view guards: `app/src/test/java/to/bitkit/ui/screens/shop/shopWebView/` (`ShopOriginTest`, `ShopWebViewClientTest`, `ShopWebViewInterfaceTest`).
- Preconditions: onboarded wallet, internet. A real payment needs funds (`./lsp` deposit and mine, `journeys/README.md`) and a live Bitrefill order; no test does this.

## What proves it
- Discover visible: `Tab-shop` and `Tab-map`, header "GIFT CARD CATEGORIES".
- Row opened: top bar text "Shop <row title>" and the Bitrefill page in the web view (screenshot; web content is not in `android layout`).
- Intro seen: the next drawer tap lands on Discover directly.
- Payment hand-off: the app handles `paymentUri` as a scan result (`AppViewModel.onScanResult`); the resulting screen was not verified.

## Not covered by tests
- `ShopIntroScreen` and its `Continue`; the Shop suggestion card; the drawer entry.
- Shop tab cards (Gift Cards, eSIMs, Refill, Travel) and every category row except "Food Delivery".
- Map tab content and swipe between tabs (pager, touch handling for the map).
- `payment_intent` to Send hand-off, blocked external link toast, load-error close to Home, web view back navigation.
- Behaviour on devices without `WEB_MESSAGE_LISTENER`.

## Gotchas
- Web view content (Bitrefill, BTC Map) is not in `android layout`; use `android screen capture` (`journeys/README.md`).
- Rows show English titles in every locale until `other__shop__categories__*` translations land; a pseudolocale cannot test it because `androidResources.localeFilters` strips `en-XA` (`journeys/shop/gift-card-category-titles.xml`).
- "VoIP" is not translatable.
- iOS has no screen router; a port reaches Discover through the drawer.
- `GiftSheet`/`Sheet.Gift` (`ui/sheets/GiftSheet.kt`) is a different feature: it redeems a gift code through Blocktank (`GiftViewModel`), not Bitrefill gift cards. Not mapped in this file.
- Bitrefill is the embed host `embed.bitrefill.com`; the payment origin check compares against `Env.BITREFILL_URL` while navigation allows any `*.bitrefill.com` (`ShopOrigin.kt`).
