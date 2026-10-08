# Contacts

`delete-newly-saved-contact.xml` checks deletion directly from Contact Saved and adding that
contact again. It is mirrored on iOS. Deleted contact screens must not remain in Back history.

`contact-payment-sharing.xml` checks disabling sharing and keeping it off after returning to
Settings. The same journey is available on iOS.

Network and storage fault injection are outside journey-runner capabilities. With contact sharing
on, make one private-list withdrawal fail and let the following public endpoint or app-registry
update fail as well. Turn off contact payments. The app must report the failure, keep the toggle off,
and retain both cleanup jobs without republishing cleared private lists. Restore connectivity and
foreground the app. Cleanup must finish while sharing stays off. Repeat with only the trailing
public endpoint or app-registry update failing.

Hold withdrawal in progress and foreground the app. It must not start another cleanup. Request
sharing on again before withdrawal finishes: publication must wait until the earlier cleanup ends,
then leave sharing on. Repeat while a foreground cleanup is already running.

Import journeys require a disposable identity with a known following list. They save local Bitkit contacts; payment sharing remains a separate step.

Continue waits for public payment setup, not private linking with every imported contact.
Private preparation runs in the background. Repeat Import All with a large following list containing
unavailable profiles, then delete a contact while preparation is running. It must not be republished
after deletion. Unavailable private-link lookups are retried after five minutes rather than on each refresh.

For Import All, include the identity's own key in the following list. Repeat with a spelling that changes only the final z-base32 padding bits, which still represents the same 32-byte key. The preview friend count and saved contacts exclude that identity, while the other follows import normally. Carry the same self-follow checks and padding-alias repeat in the matching iOS journey.

Network and storage fault injection are outside journey-runner capabilities. After the preview has
loaded, verify that importing its prepared contacts does not repeat profile lookups; saving shared
contact state still requires Pubky storage access. Simulate a failed contact batch: stay on import,
preserve previously saved contacts, and retry the entire unsaved selection without claiming partial
success. Duplicate selections and contacts already saved are skipped. Payment sharing remains a
separate step. On Android, a failed Continue on the payment-sharing screen should offer recovery
guidance and leave saved contacts intact.

## Foreground wait isolation

Hold an unrelated contact's background preparation in progress, then open a saved, linked contact
and request or pay it. The selected contact must be eligible for its own lookup before the full
contact scan finishes. Hold its public capability lookup separately: this public read must not
retain the shared-state operation queue used by payment resolution, withdrawal, and wallet backup.
Identity, link, and request execution checks still run and may wait for shared-state access.

Retry private messages for one contact while other contacts have pending outbound work. Only the
selected retry contacts should be sent to or read from in that drain. Repeat during sharing OFF,
with one withdrawal failing: OFF remains immediate, cleanup remains pending on failure, and no new
publication starts. Record action-to-result time separately from SDK lock and network waits; these
fault-injection checks do not establish staging latency or a guaranteed completion deadline.

## Background preparation

Sign in from Pubky Ring with a saved, request-capable contact while holding the selected
identity's profile lookup. After authentication and contact loading finish, Paykit target
discovery must run without waiting for the profile lookup or a maintenance poll. Verify the
Receive contact-request entry point once discovery finishes. Repeat with backgrounding or an
identity change during contact loading: automatic refresh must not start for an inactive app
or the previous identity. This requires Pubky Ring and controlled profile lookup timing,
which are not journey-runner capabilities. Android uses the same authenticated-identity gate.

With contact preparation or a private-message retry in progress, background the app. Let an active
SDK operation finish; scheduled contact work must pause between operations and retain pending
contacts. Foreground the app and verify preparation and delivery resume without adding the
contacts again. A direct payment or withdrawal is not a background retry and must retain its
normal completion behavior.

Hold an explicit request send, payment-proof completion, or sharing withdrawal in progress, then
background the app and release the operation. It must continue without waiting for foreground
contact preparation. Acceptance delivery also remains eligible after payment submission ends.
These checks require controlled operation blocking and do not guarantee execution after OS
suspension or termination.

Repeat with a controlled SDK operation held in progress: background the app, then release the
operation and verify that it completes without cancellation and the next contact waits. Delete the
profile while work is paused, then resume; no work for the deleted identity should restart.
Controlled operation blocking requires an instrumented fixture, not the standard journey runner.

Emit several proof-state notifications while backgrounded without changing proof persistence.
No observer-driven stored request refresh starts until the app is active; returning to the app
refreshes the latest state once. Repeat while payment activity is held: refresh waits until that
activity ends. A session change discards the old session's pending refresh. These notifications
and the payment-activity hold require the same controlled fixture, not a real payment.

Hold proof reconciliation during an automatic request refresh, then background the app and release
it. Reconciliation must finish, but request intake and target discovery wait for resume. Explicit
payment-completion work keeps its normal behavior. This requires controlled operation blocking.

With public sharing enabled and unchanged receive endpoints, compare retained-profile startup and
Home/resume. The foreground event and initial wallet-address observation must not start duplicate
public publication. Change the receive address while backgrounded, then resume: publish the latest
address. An expired public invoice is refreshed on resume; explicit channel and payment updates
keep their normal publication behavior. Counting publications and controlling invoice expiry require
an instrumented fixture, not the standard journey runner.
