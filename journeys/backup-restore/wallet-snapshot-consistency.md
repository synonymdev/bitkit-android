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

## Backup observation shutdown

The following controls require the same dedicated lifecycle/capture fixture;
these are not ordinary UI journey capabilities. Use isolated fixture accounts;
do not reset a funded wallet.

1. Hold a scheduled backup in its cancellable debounce, stop observation, and
   request a manual metadata backup while cancellation cleanup remains held.
   Verify a same-category manual upload and observation restart wait for cleanup.
   Release cleanup; verify pending wallet work remains required and resumes.
2. Request a new USDT recovery backup during cleanup and again after observation
   is stopped. Verify both can finish without restarting Lightning, and transient
   backup failures retain bounded retry without repeating the payment.
3. Repeatedly start and immediately stop observation. Change a wallet record only
   after the stopped listeners finish; verify no new automatic upload starts.
   Restart observation and verify a single set of listeners resumes pending work.
4. Hold setup, upload or final status persistence, then request fixture wallet
   reset. Verify both VSS clients remain unchanged until old work finishes, even
   if the reset caller is canceled. Once reset returns, simulate failed node stop
   and resume observation without clearing the cache; pending work must recover.
5. Keep wallet wipe admission closed after reset returns. Request manual retry,
   full backup and USDT persistence; all must avoid new uploads/status changes.
   After wipe finishes, new explicit backups work with observation stopped.
6. Change required data during an explicit full backup while observation runs.
   Verify the new revision uploads after the first job completes without another
   mutation.

Android stop invalidates observation immediately; reset awaits all retained work,
and restart awaits prior observer cleanup. iOS awaits its asynchronous shutdown
boundary. These platform differences do not make observation a prerequisite for
explicit USDT persistence. Component tests cover controlled cancellation, the real
SDK operation barrier, and concurrent calls with a real DataStore. They do not
establish a complete device fault journey or a deployed Paykit/VSS outage pass.

For the consumption-to-status interval, begin with synchronized settings, change the currency, and hold the required-status cache write. Stop observation or request a fixture reset that leaves the data intact, then release the write and restart without another edit. The changed settings must upload once and become synchronized. Reset must drain the write before a later cache clear; an acknowledged upload need not repeat.
