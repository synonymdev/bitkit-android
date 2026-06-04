# Bitkit Android (Native)

## About

This repository contains the **native Android app** for Bitkit.

## Development

### Prerequisites

#### 1. Firebase Configuration

Dev and testnet debug builds use a checked-in placeholder at `app/google-services.json` so a fresh clone can compile without private Firebase files.

Download `google-services.json` from the Firebase Console when you need real Firebase integration for push notifications testing:
- Debug builds: Place in `app/src/debug/google-services.json`
- mainnetRelease: Place in `app/src/mainnetRelease/google-services.json`

The debug file above is ignored by Git and takes precedence over the checked-in placeholder. To use real Firebase integration across debug variants, make sure it includes each application ID you build.

> **Note**: Placeholder config is only for local dev and testnet debug builds. FCM token registration and push notifications require real Firebase configuration. The mainnet release flavor should always use the real `mainnetRelease/google-services.json` file.

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

### Command Launcher

This repo includes a Justfile for common Gradle and script commands.

Install `just` ([more options](https://github.com/casey/just#packages)):
- macOS: `brew install just`
- Linux: `mkdir -p ~/.local/bin && curl --proto '=https' --tlsv1.2 -sSf https://just.systems/install.sh | bash -s -- --to ~/.local/bin`
- Windows: `winget install --id Casey.Just --exact`
- Windows shell: install Git for Windows or another `sh` provider for Bash-backed recipes.

Set up local env:
- `just init`
- Uncomment only the values you need in the fresh `.env` file, such as `GITHUB_ACTOR`, `GITHUB_TOKEN`, `TX_TOKEN`, and E2E build settings.

Run `just list` to see available commands. The common ones are `just init`, `just compile`, `just run`, `just build`, `just release`, `just test`, `just lint`, and `just translations pull`. `just run` prefers a physical device and falls back to an emulator.

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
just lint # run analysis + formatting check
just format # auto-fix formatting issues
```
Reports are generated in: `app/build/reports/detekt/`.

### AI tooling

The repo ships Claude Code plugins under `.claude/plugins/` to give Claude domain-specific skills for local development:

- [**blocktank-api**](.claude/plugins/blocktank-api/README.md) — `/lsp` skill for driving Blocktank LSP during Lightning testing.

Install or update all bundled plugins with:

```sh
./.claude/install-plugins.sh
```

## Test

**Commands**
```sh
just test # run unit tests

# run android tests:
just install # install
just test android # run
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
   just translations pull
   ```

### Pushing Source Strings

When you add or modify translation keys in the EN source file, push them to Transifex:

```sh
just translations push source
```

To intentionally round-trip local source and translation files back to Transifex, use `just translations push all`.

### Translation Workflow

1. **Add/modify strings** in `app/src/main/res/values/strings.xml`
2. **Push to Transifex:** `just translations push source`
3. **Translators** work on translations in Transifex
4. **Pull translations:** `just translations pull`
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
just build assembleDevRelease
# just build assembleRelease # for all flavors
```

APK is generated in `app/build/outputs/apk/_flavor_/release`. (`_flavor_` can be any of 'dev', 'mainnet', 'tnet').
Example for dev: `app/build/outputs/apk/dev/release`

### Build for Release

To build the mainnet flavor for release run:

```sh
just release
```

`just release` builds the mainnet APK, Play Store AAB, and the native debug symbols archive.

Release artifacts:

- APK: `app/build/outputs/apk/mainnet/release/`
- AAB: `app/build/outputs/bundle/mainnetRelease/`
- Native debug symbols: `app/build/outputs/native-debug-symbols/mainnetRelease/native-debug-symbols.zip`

The native debug symbols archive must come from the same `just release` build as the APK/AAB being published. Keep the filename `native-debug-symbols.zip`. If Android Gradle Plugin cannot emit this archive because native dependency metadata is already stripped, `just release` creates it from the exact merged release `.so` files.

For Play Store releases, upload the AAB as usual and verify Play Console shows native debug symbols for that exact version/build in App bundle explorer. If Play did not pick them up from the AAB, manually upload `native-debug-symbols.zip`.

For GitHub releases, attach `native-debug-symbols.zip` alongside the APK so native crashes from GitHub-distributed builds can be symbolicated later.

#### Android App Bundle (AAB)

`just release` builds both the mainnet APK and Play Store AAB. AAB is generated in `app/build/outputs/bundle/mainnetRelease/`.

### Build for E2E Testing

Pass `E2E=true` and build any flavor. By default, E2E uses a local Electrum override.

```sh
just e2e
```

#### Use Network Electrum (Staging/Mainnet)

Set `E2E_BACKEND=network` to use the network Electrum based on the build flavor:

```sh
# regtest (dev flavor)
just e2e network assembleDevRelease
# testnet (tnet flavor)
just e2e network assembleTnetRelease
# mainnet
just e2e network assembleMainnetRelease
```

#### Disable Geoblocking Checks

By default, geoblocking checks via API are enabled. To disable at build time, use the `GEO` environment variable:

```sh
just e2e no geo
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

### Local Development Setup (YOLO Mode)

To enable auto-approved permissions for Claude Code during local development:

```sh
cp .claude/settings.local.template.json .claude/settings.local.json
```

This reduces confirmation prompts for common operations (Bash, Read, Edit, Write, etc.).
Destructive operations like `rm -rf`, `git commit`, and `git push` still require confirmation.

### AI Dev Plugins

Claude Code plugins provide specialized skills for development workflows. See [`.claude/plugins/`](.claude/plugins/) for available plugins.

- [blocktank-api](.claude/plugins/blocktank-api/README.md) — Blocktank LSP API for LN testing on regtest

## License

This project is licensed under the MIT License.
See the [LICENSE](./LICENSE) file for more details.

[img_detekt]: .github/img/detekt.png
