# Widgets

In-app widgets on the Home widgets page (gallery sheet, per-widget preview and settings, edit mode) and the separate Android home-screen widgets built with Glance.

## What it does
- Seven in-app widget types (`models/WidgetType.kt`): Price, Weather, Headlines (`NEWS`), Blocks (`BLOCK`), Facts, Calculator, Suggestions. Default set and order (`WidgetsData` in `data/WidgetsStore.kt`): Suggestions, Price, Blocks, Facts, Weather, Calculator, Headlines. Sizes `SMALL`/`WIDE` (`models/WidgetSize.kt`); default wide for Price, Headlines, Suggestions.
- Widgets show on page 1 of Home (see `home.md`). A widget card shows only when it has content (Calculator always; Suggestions when cards exist; others after their first fetch).
- Add flow: `WidgetsAdd` button, then the Add Widget sheet (`Sheet.Widgets`, tag `widgets_navigation_sheet`) lists the gallery (`widgets_gallery_screen`). Tiles `WidgetListItem-price|weather|news|blocks|facts|calculator|suggestions`. Tapping a tile opens its preview with description, a small/wide size carousel, `WidgetSave` and, for a widget already added, `WidgetDelete`.
- Settings (`WidgetEdit` row on preview) exist for Price (pairs `BTC/USD|EUR|GBP|JPY`, period 1D/1W/1M/1Y), Weather (current fee fiat, current fee sats, next block inclusion), Blocks (fields block, time, date, transactions, size, fees; max 4 on), Headlines (title, source, time). Facts, Calculator, Suggestions have no settings (`WidgetType.toWidgetsEditRoute`). Edit screens have `WidgetEditReset`, `WidgetEditPreview` and rows `<name>_setting_row` (for example `BTC/EUR_setting_row`, `1W_setting_row`).
- Save writes preferences and size and adds the widget (`PriceViewModel.savePreferences`, same in the other view models), then closes the sheet and returns to the widgets page. Re-saving an existing widget keeps its position (`journeys/widgets/widgets-intro.xml`).
- Edit mode: `WidgetsEdit` (page 1 top bar, turns into a check) shows per-card overlay `<Widget name>_WidgetActionDelete|Edit|Drag` (names like `Bitcoin Price`); delete asks for confirmation (button "Yes, Delete"), edit opens the sheet at the edit route (or preview when no edit route), drag reorders; leaving edit mode saves order (`HomeViewModel.onClickEditWidgetList`).
- When widgets are off (`showWidgets` false), the gallery tiles are dimmed and disabled and `WidgetEnableInSettings` opens Widgets settings.
- Widgets settings (`WidgetsSettingsScreen`): `ShowWidgets` switch, `ResetWidgets` (confirm dialog `reset_widgets_dialog`, restores default set and returns Home), `ResetSuggestions` (`reset_suggestions_dialog`, clears dismissed cards).
- Data: `WidgetsRepo` keeps one refresh loop per enabled widget (`WidgetService.refreshInterval`: Price 1 min, Weather 8, Blocks 9, Headlines 10, Facts 1 day) and refreshes all on pull-to-refresh (Calculator and Suggestions need no service). The gallery refreshes widget data on open. Widgets are part of the backup (`WidgetsBackupV1`, see `backup.md`).
- Android home-screen widgets (launcher): Price, Headlines, Blocks, Facts, Weather as Glance widgets. Config activity for all but Facts; per-instance preferences in a separate DataStore. Refresh: WorkManager periodic job every 15 min (`AppWidgetRefreshScheduler.REFRESH_INTERVAL`) plus a catch-up alarm; per-type minimum interval Price/Blocks 15 min, Weather 30, Headlines 60, Facts local only (`AppWidgetRefreshPolicy`). Also refreshed on app start/foreground, boot, package replace and config confirm.

## How a user reaches it
- Gallery: Home -> swipe up -> `WidgetsAdd`; or `HeaderMenu` -> `DrawerWidgets` (first time shows `WidgetsIntro` with `WidgetsOnboardingViewOrganize` or `WidgetsOnboardingAddWidget`, then lands on the widgets page or the sheet).
- Edit mode: widgets page -> `WidgetsEdit`.
- Settings: `HeaderMenu` -> `DrawerSettings` -> `WidgetsSettings`.
- Deeplinks (debug build, dev mode): `bitkit://screen/widgets` (gallery), `bitkit://screen/widgets/price-edit` (also `price-preview`, `weather-preview|edit`, `blocks-preview|edit`, `headlines-preview|edit`, `facts-preview`, `calculator-preview`, `suggestions-preview`), `bitkit://screen/widgets-intro`, `bitkit://screen/widgets-settings`.
- Launcher: the system widget picker lists the five Glance widgets (`AndroidManifest.xml` receivers); `AppWidgetConfigActivity` opens on add (`android.appwidget.action.APPWIDGET_CONFIGURE`) and on reconfigure (`widgetFeatures="reconfigurable"`).

## Code
- `app/src/main/java/to/bitkit/ui/sheets/WidgetsSheet.kt`: sheet nav host, `WidgetsRoute`, route mapping.
- `ui/screens/widgets/AddWidgetsScreen.kt` (gallery), `WidgetsIntroScreen.kt`, `WidgetsGalleryViewModel.kt`, `WidgetSizeDraft.kt`, `components/` (grid, edit overlay, size carousel).
- Per widget under `ui/screens/widgets/<type>/`: `price/`, `weather/`, `blocks/`, `headlines/`: `*PreviewScreen.kt`, `*EditScreen.kt`, `*ViewModel.kt`, card; `facts/`, `calculator/`, `suggestions/`: `*PreviewScreen.kt`, view model, card.
- `ui/screens/wallets/HomeScreen.kt`, `HomeViewModel.kt`: widget page, edit mode, delete dialog, `moveWidget`.
- `ui/settings/general/WidgetsSettingsScreen.kt`: show/reset. `viewmodels/SettingsViewModel.kt`: `setShowWidgets`, `resetWidgets`, `resetDismissedSuggestions`.
- `repositories/WidgetsRepo.kt`, `data/WidgetsStore.kt`, `data/widgets/*Service.kt`, `models/widget/`: data, refresh, preferences.
- `appwidget/`: Glance widgets (`ui/<type>/*GlanceWidget.kt`, `*GlanceReceiver.kt`), `config/AppWidgetConfigActivity.kt`, `AppWidgetConfigScreen.kt`, `AppWidgetConfigViewModel.kt`, `*ConfigContent.kt`, `AppWidgetRefreshScheduler.kt`, `AppWidgetRefreshWorker.kt`, `AppWidgetRefreshPolicy.kt`, `AppWidgetRefreshReceiver.kt`; manifest receivers and `res/xml/appwidget_info_*.xml`.

## How to drive it
- `journeys/widgets/widgets-intro.xml`: first-time intro, open gallery, add Bitcoin Weather, check it is last. Needs intro unseen and Weather absent.
- `journeys/widgets/add-widgets-flow.xml`: with intro seen, menu -> Widgets -> Add Widget -> scroll to Bitcoin Calculator and open its preview.
- `journeys/deeplinks/sheet-deeplink.xml`: opens the Price editor via `bitkit://screen/widgets/price-edit`.
- `journeys/home/pull-to-refresh-rates.xml`: checks "Updated PRICE widget successfully" on pull.
- `bitkit-e2e-tests/test/specs/widgets.e2e.ts` (`@widgets`, CI shard `onchain_boost_receive_widgets`), helpers in `bitkit-e2e-tests/test/helpers/widgets.ts`:
  - `@widgets_1` add, customise (BTC/EUR, 1W), reset and delete Price; checks `PriceWidgetRow-BTC/EUR`.
  - `@widgets_2` add and delete Blocks, Headlines, Facts, Weather, Calculator.
  - `@widgets_3` Widgets settings: reset restores Price and Calculator, `ShowWidgets` off hides `WidgetsEdit`, on restores.
- Other specs: `backup.e2e.ts` (`@backup`, shard `onboarding_backup_numberpad`) adds Price and checks it after restore; `settings.e2e.ts` `@settings_12` uses `ResetSuggestions`; `hardware-wallet.e2e.ts` opens the widgets page through `openHomeWidgets()` in `helpers/navigation.ts`.
- Unit and instrumented tests exist for widget code (`app/src/test/.../appwidget`, `app/src/androidTest/.../ui/screens/widgets`), not journeys.
- Preconditions: onboarded wallet, network for widget data. Specs start from a default widget set and delete it first (`deleteAllDefaultWidgets`).

## What proves it
- Added: home tag `PriceWidget`, `BlocksWidget`, `NewsWidget`, `FactsWidget`, `WeatherWidget`, `CalculatorWidget`, `SuggestionsWidget`; saved widgets also appear as `<name>_WidgetActionDelete` in edit mode (`expectWidgetSavedInEditList`).
- Custom setting: `PriceWidgetRow-BTC/EUR` visible; after `WidgetEditReset` it is gone.
- Removed: tag gone and `Bitcoin <x>_WidgetActionDelete` gone.
- Hidden: `WidgetsEdit` not displayed.
- Gallery open: `widgets_gallery_screen`; preview: `WidgetSave`, `WidgetEdit`; editor: `WidgetEditPreview`.

## Not covered by tests
- Reordering by drag (`..._WidgetActionDrag`), small vs wide size selection, Weather/Blocks/Headlines settings, delete from a preview (`WidgetDelete`), the Edit action in edit mode for Weather/Blocks/Headlines (only Price via `openSavedWidgetPreview`).
- Suggestions and Calculator widgets: calculator input and number pad on Home, Suggestions preview content.
- Gallery with widgets off (`WidgetEnableInSettings`), widget content refresh timing, and widget restore from backup beyond Price.
- All Android home-screen (Glance) widgets: add from launcher, config activity, refresh scheduler. No journey or e2e spec (Appium spec does not touch launcher); only unit tests in `app/src/test/java/to/bitkit/appwidget`.

## Gotchas
- Gallery tiles carry no text in `android layout`; assert `WidgetListItem-*` ids or take a screenshot (`journeys/README.md`).
- `widgets-intro.xml` fails for the wrong reason if Weather is already present (re-save keeps its position).
- Tile tags for Facts and Calculator sit on the title, not the item (`WidgetPreviewTestTagPlacement.Title`).
- `widgets.e2e.ts` (`@widgets_1`) and `backup.e2e.ts` tap `WidgetSave` again if the widget does not appear (comment: flaky on GH actions).
- Outside edit mode the Suggestions widget is hidden when it has no cards; in edit mode it shows a sample grid (`SuggestionsPreviewGrid`).
- `bitkit://screen/...` links are debug-only and need dev mode (`ScreenDeepLinks.shouldQueue`).
- Unclear: how the launcher widgets are expected to be tested on a device; no doc in `docs/` or `journeys/` describes it.
