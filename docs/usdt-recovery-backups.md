# USDT recovery backups

Ordinary USDT submissions save their signed recovery snapshot through encrypted VSS independently of the full wallet backup. The callback returns successfully only after VSS acknowledges the write. A failure leaves the original signed operation in local storage; both automatic recovery and the send screen’s Retry action reconcile and resume it. Closing the screen does not cancel a retained operation. Paykit authorization, request deadlines and proof submission still apply to Paykit payments.

Each immutable `USDT_RECOVERY_V1_<sha256>` record contains a version, the core USDT snapshot and its local Paykit USDT associations. The hash covers the exact JSON bytes; readers accept both native serializers’ field ordering. VSS encrypts and obfuscates the key. Readers list decrypted names and verify every selected record’s version and content hash. Missing, corrupt or unsupported records stop restoration. A write never replaces the complete `WALLET` envelope or another recovery record.

The full `WALLET` backup remains a separate, complete snapshot with fresh Paykit SDK state. Temporary storage failures leave it pending and retry with exponential delays capped at five minutes. This backup retry does not replay a payment mutation. Paykit’s shared-state recovery and proof deduplication remain responsible for reconciling uncertain mutation outcomes under a fresh lease.

Restoration first persists a USDT recovery gate, waits for in-flight operations, and reads the complete selected journal before applying it. Existing `WALLET` USDT data is merged before the journal. The downloaded legacy payload is retained locally before journal import, and full `WALLET` replacement stays blocked while the USDT restore gate is pending. A restart repeats the merge while network recovery stays gated. Conflicting payment bindings fail closed. Restored proof evidence retains the started-payment guard but is rechecked against canonical chain state before being used as payment completion. The USDT gate clears only after core state and associations are restored. The full wallet’s separate pending restore continues to protect its unrelated state.

When a React Native backup is selected for wallet migration, its wallet metadata is retained and the USDT VSS recovery phase still runs. A failure in that phase resumes only USDT recovery on restart; it must not replace the already restored metadata with an older VSS wallet snapshot.

## Compatibility and limits

- Existing complete `WALLET` backups remain readable and are still written by the full backup routine. Their format is unchanged.
- Older app versions do not understand the independent journal. Downgrading can recover only USDT state included in the last successful complete `WALLET` backup. Do not treat downgrade recovery as supported for newer journal-only operations.
- Records are not pruned. Full snapshots can grow storage and restore work as history grows. Safe compaction needs a protocol that preserves every writer’s acknowledged records; unconditional VSS writes cannot provide that guarantee by replacing one key.
- Immutable records prevent concurrent writers from overwriting each other’s recovery data. They do not coordinate concurrent spending or make a multi-page VSS listing an atomic snapshot.

## Verification

The controlled core tests exercise actual signed-operation persistence, backup failure before broadcast, exact-operation recovery, deadlines, restart and merge ordering. Native tests cover independent backup wiring, envelope compatibility, lost acknowledgements, concurrent records, interrupted migration, pending backup retry and presentation. The `usdt-independent-recovery.xml` journey describes the complete device checks. It requires a controlled funded fixture and fault injection; passing unit tests is not evidence that this journey ran. Record the executed revision, results and any unavailable fixtures in the PR.
