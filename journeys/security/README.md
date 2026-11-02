# Security journeys

`wallet-wipe-new-profile.xml` shares its action text with iOS. Android titles the Profile Error
toast "Failed to create profile". Capture a screenshot as profile creation completes to check
that this toast is absent, because `android layout` does not expose toasts.

`pin-attempts-wipe-gate.xml` shares its action text with iOS, except the relaunch step, which
force-stops and starts the app with `adb` instead of terminating it with `xcodebuildmcp`.
