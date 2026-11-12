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

1. Run `just deps`. It runs `scripts/update-verification-metadata.sh`, which needs GitHub Packages credentials (see [GitHub Packages setup](../README.md#2-github-packages-setup)). It takes a long time, because it runs the CI and release build tasks so that Gradle records every artifact they resolve.
2. Review the diff of `gradle/verification-metadata.xml`. New entries should match only the coordinates you changed.
3. Commit the file in the same PR as the dependency change.

The script keeps existing entries and the `<configuration>` block. It fails if a host-specific artifact lacks its variants for other platforms.

## Dependabot

Dependabot does not update `gradle/verification-metadata.xml`, so its Gradle PRs fail CI. To fix one, check out its branch, run `just deps`, commit the file and push.

After someone else pushes to a Dependabot branch, Dependabot stops rebasing that PR. If the PR needs a fresh update, comment `@dependabot recreate` and regenerate the file again.

## Host-specific artifacts

Google Maven publishes `aapt2` once per operating system. Gradle records only the variant for the machine that runs `just deps`, so the script downloads the linux, osx and windows variants from Google Maven and adds their checksums. The file then works on every OS.

## Device test artifacts

Some artifacts are used only by connected device tests, such as the `com.android.tools.utp` packages that run instrumented tests. `just deps` does not run device tests, so it does not record them.

These entries come from CI failures. When a device test job fails verification, download each artifact named in the failure from Google Maven, compute its checksum with `shasum -a 256 <file>`, and add the entry to the file under its component.

## Stale entries

Entries for versions the build no longer uses are harmless. To prune them, delete every child of `<components>`, keep `<configuration>`, and run `just deps`. Then add the device test entries back as described in [Device test artifacts](#device-test-artifacts).

## Local overrides

Builds that use local artifacts, through `-PuseMavenLocal=...` or `-PbitkitCoreAar=...`, fail verification by design, because those artifacts have no recorded checksum. For those local builds only, run Gradle in lenient mode, which reports mismatches without failing: `./gradlew --dependency-verification lenient <task>`.

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
