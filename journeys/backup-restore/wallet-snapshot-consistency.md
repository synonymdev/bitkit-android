# Wallet snapshot consistency

Use an isolated test wallet and backup destination. Controlled SDK/capture/upload
gates and inspection of stored backup envelopes are not journey Capabilities;
these checks require a dedicated fault-injection fixture. Do not inject into a
funded personal wallet or shorten payment safety waits.

1. Queue a wallet backup behind a Paykit mutation, then release the mutation.
   Verify the exported SDK state includes it and a consistent snapshot uploads.
2. Hold snapshot capture after the SDK export, change private payment state, and
   release capture. Verify the discarded snapshot never uploads and a fresh,
   consistent capture succeeds when activity settles.
3. Keep changing state through each capture attempt. Verify no mixed snapshot
   uploads, the wallet remains pending backup, and automatic backup succeeds
   after the fixture becomes quiet without requiring another state change.
4. Change state during a held upload. Verify completion does not mark the newer
   state backed up, and the subsequent upload contains that state.
5. Cancel capture or replace the identity while capture/retry is held. Verify no
   mixed-identity snapshot uploads. Verify SDK/export/upload failures and unsigned
   payment attempts still fail without being reported as successful backups.
6. Restore a successful captured envelope into an isolated fixture. Verify its
   SDK state, private consumption state, reservations, proofs and signed active
   payment attempt agree. Do not broadcast a payment as part of this check.

Record the tested commit, gate locations, attempts and upload counts, and the
restored state. Unit coverage does not establish a device pass or a maximum
backup delay during sustained activity.
