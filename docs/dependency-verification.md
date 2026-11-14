# Gradle dependency verification

Gradle checks every dependency, plugin, POM file and Gradle module file it downloads against the sha256 checksums recorded in `gradle/verification-metadata.xml`. Verification runs in strict mode, the Gradle default, in every local and CI build. A build fails when an artifact has no recorded checksum or its checksum differs.

The `<configuration>` block at the top of the file sets these rules:

- Metadata files (POM and `.module`) are verified, not only jars and AARs.
- Signatures are not checked. See [Why sha256 only](#why-sha256-only).
- `-javadoc.jar` and `-sources.jar` files are trusted without a checksum, so IDE downloads do not fail the build.

## When to regenerate

Regenerate the file when you change any of these:

- `gradle/libs.versions.toml`
- Plugin versions
- Repositories or plugins in `settings.gradle.kts`
- Dependencies in `app/build.gradle.kts`

To regenerate:

1. Run `just deps`. It runs `scripts/update-verification-metadata.sh`, which needs GitHub Packages credentials (see [GitHub Packages setup](../README.md#2-github-packages-setup)). It takes a long time, because it runs the CI and release build tasks so that Gradle records every artifact they resolve. If `keystore.properties` exists, the release tasks sign with it instead of the debug keystore, so the `storeFile` it names must exist.
2. Review the diff of `gradle/verification-metadata.xml`. New components and versions are expected, including new transitive dependencies of what you changed. What needs investigating is a changed checksum or an `also-trust` value on an existing entry: find the cause instead of committing it. The script fails and prints the line numbers when the file contains an `also-trust` value.
3. Commit the file in the same PR as the dependency change.

The script keeps existing entries and the `<configuration>` block. It fails if a host-specific artifact lacks its variants for other platforms. It passes `-PuseMavenLocal=false` and stops if `bitkitCoreAar` is set, so local artifacts are never recorded. If a Gradle task fails, the script still finishes the other steps but exits with an error; do not commit the file until `just deps` passes.

`just deps` always runs Gradle with a temporary Gradle home, which it deletes afterwards. It copies your `gradle.properties` into it for the GitHub Packages credentials and downloads every artifact again, because Gradle does not record parent POMs and BOMs that it serves from its cache. So it takes longer and needs several GB of free disk while it runs. New entries record the checksums of these fresh downloads, so a new entry is trusted on first use: it is only as trustworthy as the repository that served it at that time.

The script records release dependencies with the tnet flavor, because a mainnet build needs secret config. This works only while no dependency or plugin differs per flavor. If you add a mainnet-only or other flavor-specific dependency or plugin, extend the task list in the script to cover it. PR CI never builds mainnet, so a missing entry would only show up when the mainnet release build fails verification.

## Dependabot

Dependabot does not update `gradle/verification-metadata.xml`, so its Gradle PRs fail CI. To fix one, check out its branch, run `just deps`, commit the file and push.

After someone else pushes to a Dependabot branch, Dependabot stops rebasing that PR. If the PR needs a fresh update, comment `@dependabot recreate` and regenerate the file again.

## Host-specific artifacts

Google Maven publishes `aapt2` once per operating system. Gradle records only the variant for the machine that runs `just deps`, so the script downloads the linux, osx and windows variants from Google Maven and adds their checksums. The file then works on every OS.

The script fails when any artifact named for one of these platforms lacks its other variants. To fix it, add the missing variant's checksum from the artifact's repository. If the dependency does not publish that platform, note the exception in this section and adjust the check in the script.

## Device test artifacts

Some artifacts are used only by connected device tests, such as the `com.android.tools.utp` packages that run instrumented tests. `just deps` does not run device tests, so it does not record them.

These entries come from CI failures. When a device test job fails verification, download each artifact named in the failure from Google Maven, compute its checksum with `shasum -a 256 <file>`, and add the entry to the file under its component.

## Stale entries

Entries for versions the build no longer uses are harmless. To prune them, delete every child of `<components>`, keep `<configuration>`, and run `just deps`. Then add the device test entries back as described in [Device test artifacts](#device-test-artifacts).

## Local overrides

Builds that use local artifacts fail verification by design:

- With `-PuseMavenLocal=...`, artifacts published to Maven local do not match the recorded checksums.
- With `-PbitkitCoreAar=...`, Gradle does not check the local AAR itself, because it is a file dependency. The build also adds `org.jetbrains.kotlinx:atomicfu:0.33.0`, which has no recorded checksum.

For those local builds only, run Gradle in lenient mode: `./gradlew --dependency-verification lenient <task>`. Lenient mode reports problems instead of failing for every dependency in that build, not only the local ones, so use it for local experiments only.

Never use lenient mode in CI or for a release build.

## What verification does not cover

Gradle dependency verification covers only what Gradle resolves from repositories. These inputs are outside it:

- Jars and AARs in `app/libs` (`btleplug.aar`, `jni-utils.jar`), which are tracked in Git.
- A local Bitkit Core AAR passed with `-PbitkitCoreAar`.
- The Gradle distribution, which `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties` pins.
- The daemon JDK that Gradle provisions through foojay, as configured in `gradle/gradle-daemon-jvm.properties`.
- Android SDK and NDK packages.
- Robolectric `android-all` jars, which Robolectric downloads itself at test time.

## Why sha256 only

A sha256 checksum pins the exact bytes of each artifact. PGP signature checks would also need a maintained keyring, and many Google Maven artifacts and the GitHub Packages artifacts may be unsigned. Signature checks can be added later.
