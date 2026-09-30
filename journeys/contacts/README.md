# Contacts

Import journeys require a disposable identity with a known following list. They save local Bitkit contacts; payment sharing remains a separate step.

Network and storage fault injection are outside journey-runner capabilities. Manually disable connectivity after the preview has loaded: importing the prepared contacts must still finish. Simulate a failed local save: stay on import, preserve successful saves, and retry only missing contacts without claiming complete success. On Android, a failed Continue on the payment-sharing screen should offer recovery guidance and leave saved contacts intact.
