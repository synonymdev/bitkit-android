# Bitkit Android (Native)

> [!CAUTION]
> ⚠️This is **NOT** the repository of the Bitkit app from the app stores!<br>
> ⚠️Work-in-progress<br>
> The Bitkit app repository is here: **[github.com/synonymdev/bitkit](https://github.com/synonymdev/bitkit)**

---

## About

This repository contains a **new native Android app** which is **not ready for production**.

## Development

### Prerequisites

#### 1. Download `google-services.json` to `app/` from FCM Console.

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

- For LNURL dev testing see [bitkit-docker](https://github.com/ovitrif/bitkit-docker)

### Lint

This project uses detekt with default ktlint and compose-rules for android code linting.

Recommended Android Studio plugins:
- EditorConfig
- Detekt

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
   - Create a `.transifexrc` file in your home directory (`~/.transifexrc`) with your API token:
     ```ini
     [https://www.transifex.com]
     rest_hostname = https://rest.api.transifex.com
     token         = YOUR_API_TOKEN_HERE
     ```
   - You can get your API token from your [Transifex account settings](https://www.transifex.com/user/settings/api/)
   - The CLI will prompt you for an API token if one is not configured

3. **Pull translations**:
   ```sh
   ./scripts/pull-translations.sh
   ```

## Build

### Bitcoin Networks

The build config supports building 3 different apps for the 3 bitcoin networks (mainnet, testnet, regtest) via the 3 build flavors:
- `dev` flavour = regtest
- `mainnet` flavour = mainnet
- `tnet` flavour = testnet

### Build for E2E Testing

Simply pass `E2E=true` as environment variable and build any flavor.

```sh
E2E=true ./gradlew assembleDevRelease
```

#### Disable Geoblocking Checks

By default, geoblocking checks via API are enabled. To disable at build time, use the `GEO` environment variable:

```sh
GEO=false E2E=true ./gradlew assembleDevRelease
```

### Build for Release

**Prerequisites**  
Setup the signing config:
- Add the keystore file to root dir (i.e. `release.keystore`)
- Setup `keystore.properties` file in root dir (`cp keystore.properties.template keystore.properties`)

**Routine**

Increment `versionCode` and `versionName` in `app/build.gradle.kts`, then run:
```sh
./gradlew assembleDevRelease
# ./gradlew assembleRelease # for all flavors
```

APK is generated in `app/build/outputs/apk/_flavor_/release`. (`_flavor_` can be any of 'dev', 'mainnet', 'tnet').
Example for dev: `app/build/outputs/apk/dev/release`

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
