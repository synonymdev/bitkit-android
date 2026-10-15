# Shop Contact Import

These manual cases require a buyer marker writer and an initially unlinked seller
storefront. The existing marketplace fixture starts with linked contacts and does
not provide this first-checkout setup or controlled storage/network interruptions.
Run the same cases on iOS and Android. Record the app commit, SDK version, buyer and
seller public keys, marker publication time, request id and lifecycle transitions.
Never record credentials or Paykit secrets.

## Contract

Shop writes a permanent marker at
`pubky://<buyer>/pub/pubky.app/migrations/seller-auto-follow/v1/<seller>.json` with
matching `seller` and positive `completedAt` fields. The seller must publish
`pubky://<seller>/pub/pubky.app/marketplace/v1/shop.json` with `schemaVersion: 1`,
`recordType: "shop"` and matching `ownerPubky`. Any app with the buyer's pubky.app
write capability can create a marker; a storefront is not proof that Shop wrote it.

Bitkit processes up to ten markers per existing request poll or foreground trigger.
An empty page restarts discovery; a short page does not end it. Large histories and
failed reads can take several polls. This is not a fixed checkout-latency guarantee.
Only missing, unblocked contacts are imported. Existing labels and records remain
unchanged, and contact deletion's persisted peer block prevents automatic re-addition.
The normal contact-change observer starts linking; importing never authorizes payment.

## Cases

1. Start with no saved contact or link to the seller. Publish a valid historical
   marker and storefront, then cold-open Bitkit with contact payments enabled.
   Verify the seller appears without opening Contacts and linking begins. Repeat
   with Bitkit already open, then with a background/resume transition.
2. Keep Bitkit on the buyer's payment screen. Publish a new marker and an actual
   seller Payment Request. Verify a later existing poll imports and links the
   seller, then the exact request becomes available. Stop at payment review: no
   approval, funding, mining or broadcast is part of this test.
3. Seed more than 100 distinct markers and storefronts, including a short
   intermediate page. Allow successive polls until the final seller is imported.
   Add a marker sorting before the current cursor; verify it appears after the
   scan restarts. Previously handled sellers must not cause repeated contact writes.
4. Edit one saved seller's label, delete another using Bitkit's normal contact
   removal, and block a third. Retain their Shop markers. Repeat polls and relaunch;
   verify the label stays edited and removed/blocked contacts stay absent or blocked.
   Repeat an edit/removal while marker discovery is in flight, before the save.
5. Supply a malformed marker, mismatched seller, self key, wrong-owner storefront,
   wrong record type/schema and missing storefront. Verify no invalid contact is
   added. Correct one failed storefront and verify it is retried on a later scan.
6. Interrupt discovery, then interrupt the contact refresh after an import commits.
   Restore connectivity and resume/poll. Verify the committed contact becomes
   visible and starts normal linking, without duplicate rows or lost edits.
7. Hold a contact read started before import. Let import save, then release that
   old read. Verify a second fresh read displays the imported contact. Background
   or change identity while discovery/save is waiting; verify no cross-identity
   writes or stale UI results. An admitted SDK write retains normal recovery rules.
8. Start payment submission while import is queued. Verify the background import
   waits and does not alter the payment's approval, request, winner or recovery
   state. Repeat with a plain send, a contact payment and a Payment Request. Hold
   the payment operation before broadcast and dismiss its sheet; imports must stay
   paused until the operation finishes or is actually cancelled, not merely until
   the sheet closes. Cancel the importer by backgrounding or disabling contact
   payments during that wait, then release payment activity; the cancelled import
   must not start a write. Resume or re-enable to permit a fresh import. Change
   identity during the wait and verify no old-identity import starts. A write that
   was already admitted before cancellation must still finish safely.
9. Hold a foreground contact refresh, then open a contact deep link that joins the
   same read. Cancel only the foreground refresh with a connectivity change and
   release the read. Verify the contact link opens using the fresh contacts without
   another lifecycle trigger. Repeat cancelling the link instead; the foreground
   refresh must finish. Cancel both or sign out before release; no stale contacts
   may be published. Controlled read suspension/cancellation is not a journey capability.

Record observed timing and failures separately from unit-test results. Passing
these cases without a payment does not prove Shop order-paid or delivery acceptance.
