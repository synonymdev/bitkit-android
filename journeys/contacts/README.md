# Contacts

`contact-payment-sharing.xml` checks disabling sharing and keeping it off after returning to
Settings. The same journey is available on iOS.

Network and storage fault injection are outside journey-runner capabilities. With contact sharing
on, make one private-list withdrawal fail and let the following public endpoint or app-registry
update fail as well. Turn off contact payments. The app must report the failure, keep the toggle off,
and retain both cleanup jobs without republishing cleared private lists. Restore connectivity and
foreground the app. Cleanup must finish while sharing stays off. Repeat with only the trailing
public endpoint or app-registry update failing.

Import journeys require a disposable identity with a known following list. They save local Bitkit contacts; payment sharing remains a separate step.

For Import All, include the identity's own key in the following list. Repeat with a spelling that changes only the final z-base32 padding bits, which still represents the same 32-byte key. The preview friend count and saved contacts exclude that identity, while the other follows import normally. Carry the same self-follow checks and padding-alias repeat in the matching iOS journey.

Network and storage fault injection are outside journey-runner capabilities. Manually disable connectivity after the preview has loaded: importing the prepared contacts must still finish. Simulate a failed local save: stay on import, preserve successful saves, and retry only missing contacts without claiming complete success. On Android, a failed Continue on the payment-sharing screen should offer recovery guidance and leave saved contacts intact.
