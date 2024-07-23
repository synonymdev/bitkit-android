# FCM Push Notifications Tester
Simple app to auth with Google and test push notifications via FCM.
FCM should work both for Android via GCM, as well as Apple via APNS.

Can be used either to grab a Bearer auth token for use in Postman, or to test predefined push notification messages by sending them to a specific device via FCM.

## Prerequisite
1. Download from FCM/Google Cloud the service account JSON certificate into `./service-account.json`.
1. `npm i` or `yarn`

## Usage

### Easiest way to use it:

1. Import `./fcm.postman_collection.json` in Postman
1. Mint a short-lived access token for [FCM HTTP v1 API](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages):
   ```sh
   npm start
   ```
1. Add the printed access token to your Postman environment in a variable named `bearerToken`.
1. Run the app, grab the `FCM Registration token` from logcat and add it to your Postman environment, in a variable named `deviceToken`.
1. Copy one of the sample payloads from `./messages` to the request body in Postman.
1. Run your request and check logcat for entries of the `FCM` tag. Before processing a remote message, the first loged line says `--- new FCM ---`.
   - Whenever your access token expires, repeat step 1.
