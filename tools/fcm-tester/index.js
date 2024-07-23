/**
 * Firebase Cloud Messaging (FCM) can be used to send messages to clients on iOS, Android and Web.
 *
 * This sample uses FCM to send two types of messages to clients that are subscribed to the `news`
 * topic. One type of message is a simple notification message (display message). The other is
 * a notification message (display notification) with platform specific customizations. For example,
 * a badge is added to messages that are sent to iOS devices.
 */
import { request as _request } from "https";
import { google } from "googleapis";
import key from "./service-account.json" assert { type: "json" };

const PROJECT_ID = "snbkandroid";
const HOST = "fcm.googleapis.com";
const PATH_SEND = "/v1/projects/" + PROJECT_ID + "/messages:send";
const MESSAGING_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const SCOPES = [MESSAGING_SCOPE];

function getAccessToken() {
  return new Promise(function (resolve, reject) {
    const jwtClient = new google.auth.JWT(
      key.client_email,
      null,
      key.private_key,
      SCOPES,
      null
    );
    jwtClient.authorize(function (err, tokens) {
      if (err) {
        reject(err);
        return;
      }
      resolve(tokens.access_token);
    });
  });
}

/**
 * Send HTTP request to FCM with given message.
 *
 * @param {object} fcmMessage will make up the body of the request.
 */
function sendFcmMessage(fcmMessage) {
  getAccessToken().then(function (accessToken) {
    const options = {
      hostname: HOST,
      path: PATH_SEND,
      method: "POST",
      // [START use_access_token]
      headers: {
        Authorization: "Bearer " + accessToken,
      },
      // [END use_access_token]
    };

    const request = _request(options, function (resp) {
      resp.setEncoding("utf8");
      resp.on("data", function (data) {
        console.log("Message sent to Firebase for delivery, response:");
        console.log(data);
      });
    });

    request.on("error", function (err) {
      console.log("Unable to send message to Firebase");
      console.log(err);
    });

    request.write(JSON.stringify(fcmMessage));
    request.end();
  });
}

/**
 * Construct a JSON object that will be used to customize
 * the messages sent to iOS and Android devices.
 */
function buildOverrideMessage() {
  const fcmMessage = buildCommonMessage();
  const apnsOverride = {
    payload: {
      aps: {
        badge: 1,
      },
    },
    headers: {
      "apns-priority": "10",
    },
  };

  const androidOverride = {
    notification: {
      click_action: "android.intent.action.MAIN",
    },
  };

  fcmMessage["message"]["android"] = androidOverride;
  fcmMessage["message"]["apns"] = apnsOverride;

  return fcmMessage;
}

/**
 * Construct a JSON object that will be used to define the
 * common parts of a notification message that will be sent
 * to any app instance subscribed to the news topic.
 */
function buildCommonMessage() {
  return {
    message: {
      topic: "news",
      notification: {
        title: "FCM Notification",
        body: "Notification from FCM",
      },
    },
  };
}

const actions = {
  token: () => {
    return new Promise(function (resolve, reject) {
      getAccessToken()
        .then(function (accessToken) {
          console.log("\n🔐 ACCESS TOKEN:");
          console.log(accessToken);
          resolve(accessToken);
        })
        .catch((err) => {
          console.error("Error fetching access token");
          reject(err);
        });
    });
  },
  commonMessage: () => {
    const commonMessage = buildCommonMessage();
    console.log(
      "FCM request body for message using common notification object:"
    );
    console.log(JSON.stringify(commonMessage, null, 2));
    sendFcmMessage(buildCommonMessage());
  },
  overrideMessage: () => {
    const overrideMessage = buildOverrideMessage();
    console.log("FCM request body for override message:");
    console.log(JSON.stringify(overrideMessage, null, 2));
    sendFcmMessage(buildOverrideMessage());
  },
};

const arg = process.argv[2];
if (arg) {
  if (arg == "token") {
    actions.token();
  } else if (arg == "message-common") {
    actions.commonMessage();
  } else if (arg == "message-override") {
    actions.overrideMessage();
  }
} else {
  console.log(
    "Invalid command. Please append one of the following arguments:\n" +
      "node index.js token" +
      "\n" +
      "node index.js message-common" +
      "\n" +
      "node index.js message-override"
  );
}
