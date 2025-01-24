# Bitkit Android

Bitkit Android Native app.

## Prerequisites

1. Download `google-services.json` to `./app` from FCM Console
2. Run Polar with the default initial network

## Dev Tips

- To communicate from host to emulator use:
  `127.0.0.1` and setup port forwarding via ADB, eg. `adb forward tcp:9777 tcp:9777`
- To communicate from emulator to host use:
  `10.0.2.2` instead of `localhost`

## Localization

Localization files are synced from the Transifex "Bitkit" project and converted to native format.  
Currently only pull is supported, meaning there's no way to push native-only strings for translations.

To (re-)sync localization files:

#### 1. Pull the latest translations and sources:

```sh
tx pull -s -t --mode translator -f -a
```

This will store the STRUCTURED_JSON files in a temp dir for the converter scripts at: `./tools/translations-converter/tx`

#### 2. CD into the script dir:  
```sh
cd tools/translations-converter
```

If needed, run `npm i` to install or update dependencies.

#### 3. Clean empty strings in JSONs
```sh
node clean_empty_strings.js
```

#### 4. Convert to native

```sh
# for Android run:
node convert_android.js

# or, for iOS run:
node convert_ios.js
```

Native localization files are saved in the specific directories where Android or iOS expects them:
- **Android**: `./app/src/main/res/values-<android_locale>/strings.xml`
- **iOS**: `./Bitkit/Resources/Localization/<ios_locale>.lproj/Localizable.strings`

### Translation keys
- Are prefixed with the filename, i.e. strings in `onboarding` have `onboarding__` prefix
- Nested JSON strings are delimited in keys with double-underscore `__`,  for example:
  ```js
  // This from RN:
  const { t } = useTranslation('wallet');
  const title = t('savings.title');
  // becomes: "wallet__savings__title"
  ```

For **translation values**, the following are preserved and need to be handled programmatically:
- Placeholders, eg. `{totalBalance}`
- Pluralisation, eg. `{count, plural, one {INPUT} other {INPUTS (#)}}`
- Tags, eg. `<accent>`,`<bold>`
