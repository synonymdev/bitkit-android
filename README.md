# Bitkit Android (Native)

## About

This repository contains the **native Android app** for Bitkit.

## Development

### Prerequisites

#### 1. Firebase Configuration

Download `google-services.json` from the Firebase Console for each of the following build flavor groups,:
- dev/tnet/mainnetDebug: Place in `app/google-services.json`
- mainnetRelease: Place in `app/src/mainnetRelease/google-services.json`

> **Note**: Each flavor requires its own Firebase project configuration. The mainnet flavor will fail to build without its dedicated `google-services.json` file.

#### 2. GitHub Packages setup

Some internal libraries are distributed via GitHub Packages. Configure credentials so Gradle can resolve them.

1) Create a GitHub token with `read:packages` scope.

2) Provide credentials for Gradle (choose one):

   - Environment variables
     - `GITHUB_ACTOR` (your username)
     - `GITHUB_TOKEN` (token with `read:packages`)

   - `PROJECT_ROOT/local.properties` or `~/.gradle/gradle.properties`
     - `gpr.user=YOUR_GITHUB_USERNAME`
     - `gpr.key=YOUR_GITHUB_TOKEN`

See also:
- [bitkit-core android bindings](https://github.com/synonymdev/bitkit-core/tree/master/bindings/android#installation)
- [vss-rust-client-ffi android bindings](https://github.com/synonymdev/vss-rust-client-ffi/tree/master/bindings/android#installation)

### References

- For LNURL dev testing see [bitkit-docker](https://github.com/synonymdev/bitkit-docker)

### Lint

This project uses detekt with default ktlint and compose-rules for android code linting.

### IDE Plugins
The following IDE plugins are recommended for development with Android Studio or IntelliJ IDEA:
- [Compose Color Preview](https://plugins.jetbrains.com/plugin/21298-compose-color-preview)
- [Compose Stability Analyzer](https://plugins.jetbrains.com/plugin/28767-compose-stability-analyzer)
- [detekt](https://plugins.jetbrains.com/plugin/10761-detekt)
  <details>
  <summary>See screenshot on how to setup the Detekt plugin after installation.</summary>
  
  ![Detekt plugin setup][img_detekt]
  </details>

**Commands** 
```sh
./gradlew detekt # run analysis + formatting check
./gradlew detekt --auto-correct # auto-fix formatting issues
```
Reports are generated in: `app/build/reports/detekt/`.

## Test

**Commands**
```sh
./gradlew testDevDebugUnitTest # run unit tests

# run android tests:
./gradlew installDevDebug # install
./gradlew connectedDevDebugAndroidTest # run
```

## Localization

### Pulling Translations

To pull the latest translations from Transifex:

1. **Install Transifex CLI** (if not already installed):
   - Follow the installation instructions: [Transifex CLI Installation](https://developers.transifex.com/docs/cli)

2. **Authenticate with Transifex** (if not already configured):
   - Set the `TX_TOKEN` environment variable with your API token:
     ```sh
     export TX_TOKEN="YOUR_API_TOKEN_HERE"
     ```
   - You can get your API token from [Transifex account settings](https://www.transifex.com/user/settings/api/)
   - Add it to `~/.zshrc` or other shell rc file to persist across sessions

3. **Pull translations**:
   ```sh
   ./scripts/pull-translations.sh
   ```

### Pushing Source Strings

When you add or modify translation keys in the EN source file, push them to Transifex:

```sh
tx push --source
```

### Translation Workflow

1. **Add/modify strings** in `app/src/main/res/values/strings.xml`
2. **Push to Transifex:** `tx push --source`
3. **Translators** work on translations in Transifex
4. **Pull translations:** `./scripts/pull-translations.sh`
5. **Commit** the updated translation files

## Build

### Bitcoin Networks

The build config supports building 3 different apps for the 3 bitcoin networks (mainnet, testnet, regtest) via the 3 build flavors:
- `dev` flavour = regtest
- `mainnet` flavour = mainnet
- `tnet` flavour = testnet

### Build for Internal Testing

**Prerequisites**  
Setup the signing config:
- Add the keystore file to root dir (i.e. `internal.keystore`)
- Setup `keystore.properties` file in root dir (`cp keystore.properties.template keystore.properties`)

**Routine**

Increment `versionCode` and `versionName` in `app/build.gradle.kts`, then run:
```sh
./gradlew assembleDevRelease
# ./gradlew assembleRelease # for all flavors
```

APK is generated in `app/build/outputs/apk/_flavor_/release`. (`_flavor_` can be any of 'dev', 'mainnet', 'tnet').
Example for dev: `app/build/outputs/apk/dev/release`

### Build for Release

To build the mainnet flavor for release run:

```sh
./gradlew assembleMainnetRelease
```

#### Android App Bundle (AAB)

For Play Store submission, build an AAB instead of APK:

```sh
./gradlew bundleMainnetRelease
```

AAB is generated in `app/build/outputs/bundle/mainnetRelease/`.

### Build for E2E Testing

Pass `E2E=true` and build any flavor. By default, E2E uses a local Electrum override.

```sh
E2E=true ./gradlew assembleDevRelease
```

#### Use Network Electrum (Staging/Mainnet)

Set `E2E_BACKEND=network` to use the network Electrum based on the build flavor:

```sh
# regtest (dev flavor)
E2E=true E2E_BACKEND=network ./gradlew assembleDevRelease
# testnet (tnet flavor)
E2E=true E2E_BACKEND=network ./gradlew assembleTnetRelease
# mainnet
E2E=true E2E_BACKEND=network ./gradlew assembleMainnetRelease
```

#### Disable Geoblocking Checks

By default, geoblocking checks via API are enabled. To disable at build time, use the `GEO` environment variable:

```sh
GEO=false E2E=true ./gradlew assembleDevRelease
```

## Contributing

### AI Code Review with Claude

This repository has Claude Code integrated for on-demand AI assistance on issues and pull requests.

#### How to Use

Mention `@claude` in any PR comment, issue, or review to trigger Claude:

| Command | Description |
|---------|-------------|
| `@claude review` | Request a code review of the PR |
| `@claude /review` | Same as above (slash command) |
| `@claude review focus on security` | Review with specific focus |
| `@claude explain this change` | Ask questions about the code |
| `@claude fix the null pointer issue` | Request Claude to implement a fix |
| `@claude /help` | Show available commands |

#### Notes

- Claude follows the project guidelines defined in `CLAUDE.md`
- **Automatic reviews** run on every PR open and push (updates same comment)
- **On-demand assistance** via `@claude` mentions in comments/issues
- Claude can read CI results to provide context-aware feedback
- For implementation requests, Claude will create commits on your branch

#### Example

```
@claude review

Please focus on:
- Kotlin idioms and best practices
- Potential memory leaks
- Thread safety in coroutines
```

#### Local Development Setup (YOLO Mode)

To enable auto-approved permissions for Claude Code during local development:

```sh
cp .claude/settings.local.template.json .claude/settings.local.json
```

This reduces confirmation prompts for common operations (Bash, Read, Edit, Write, etc.).
Destructive operations like `rm -rf`, `git commit`, and `git push` still require confirmation.

## License

This project is licensed under the MIT License.
See the [LICENSE](./LICENSE) file for more details.

[img_detekt]: .github/img/detekt.png
