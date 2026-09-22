{
  description = "Bitkit Android dev shell (JDK 17 + Android SDK 36/NDK r28b via androidenv)";

  # Provides the full toolchain to run: just compile / just build / just test / just lint
  #
  # Usage:
  #   nix develop          # enter the shell
  #   just compile         # or: ./gradlew assembleDevDebug
  #
  # Notes:
  #   - First `nix develop` downloads ~2 GB of SDK/NDK; first gradle run
  #     downloads the wrapper + all maven deps.
  #   - Emulator/system images are excluded to keep the closure small.
  #     For `just test android` without a physical device, flip the two
  #     flags in androidenv below.
  #   - On NixOS, adb access to a physical device needs udev rules:
  #       services.udev.packages = [ pkgs.android-udev-rules ];
  #   - A fully sandboxed `nix build` of the APK is not provided: gradle
  #     needs network access for maven deps, which would require vendoring
  #     the whole dependency graph (e.g. via gradle2nix).

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { nixpkgs, ... }:
    let
      # Only x86_64-linux is verified; the rest should work via androidenv.
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (
        system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              # Android SDK/NDK are unfree-licensed
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };

          buildToolsVersion = "36.0.0";
          # Keep in sync with ndk_ver in Justfile
          ndkVersion = "28.1.13356709";

          androidSdk = pkgs.androidenv.composeAndroidPackages {
            cmdLineToolsVersion = "latest";
            platformToolsVersion = "latest";
            platformVersions = [ "36" ]; # compileSdk/targetSdk
            buildToolsVersions = [ buildToolsVersion ];
            includeNDK = true;
            ndkVersions = [ ndkVersion ];
            includeEmulator = false;
            includeSystemImages = false;
            # No native code is built (rust libs ship as maven artifacts)
            includeCmake = false;
          };

          sdkRoot = "${androidSdk.androidsdk}/libexec/android-sdk";
        in
        {
          default = pkgs.mkShell {
            packages = with pkgs; [
              jdk17 # AGP 9.3 / Gradle 9.5 require JDK 17+
              just
              git
            ];

            JAVA_HOME = "${pkgs.jdk17.home}";
            ANDROID_HOME = sdkRoot;
            ANDROID_SDK_ROOT = sdkRoot;
            ANDROID_NDK_ROOT = "${sdkRoot}/ndk/${ndkVersion}";

            shellHook = ''
              export PATH="$ANDROID_HOME/platform-tools:$PATH"

              # AGP fetches aapt2 from maven as an unpatched ELF binary that
              # cannot run on NixOS; point it at the patched one from the SDK.
              aapt2="$ANDROID_HOME/build-tools/${buildToolsVersion}/aapt2"
              gradle_props="''${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
              mkdir -p "$(dirname "$gradle_props")"
              touch "$gradle_props"
              if grep -qs '^android.aapt2FromMavenOverride=' "$gradle_props"; then
                # Refresh overrides previously written by this hook (stale store
                # paths break after a flake update + gc); leave custom ones alone.
                sed -i "s|^android.aapt2FromMavenOverride=/nix/store/.*|android.aapt2FromMavenOverride=$aapt2|" "$gradle_props"
              else
                echo "android.aapt2FromMavenOverride=$aapt2" >> "$gradle_props"
                echo ">> added aapt2 override to $gradle_props"
              fi

              echo "bitkit-android dev shell"
              echo "  java:         $(java -version 2>&1 | head -1)"
              echo "  ANDROID_HOME: $ANDROID_HOME"
            '';
          };
        }
      );
    };
}
