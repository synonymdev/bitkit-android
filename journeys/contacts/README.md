# Contacts

Import journeys require a disposable identity with a known following list. They save local Bitkit contacts; payment sharing remains a separate step.

For Import All, include the identity's own key in the following list. The preview friend count and saved contacts exclude that identity, while the other follows import normally. Carry the same self-follow checks in the matching iOS journey.

Network and storage fault injection are outside journey-runner capabilities. Manually disable connectivity after the preview has loaded: importing the prepared contacts must still finish. Simulate a failed local save: stay on import, preserve successful saves, and retry only missing contacts without claiming complete success. On Android, a failed Continue on the payment-sharing screen should offer recovery guidance and leave saved contacts intact.
