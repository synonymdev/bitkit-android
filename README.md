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
