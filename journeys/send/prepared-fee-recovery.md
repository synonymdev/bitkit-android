# Prepared transaction fee recovery

These checks need a disposable funded regtest wallet and controlled native transaction-history, broadcast-response or persistence faults. The journey capabilities do not provide those faults; ordinary payment success alone does not cover them. Never use an existing wallet or start a new payment after an unknown outcome. An explicit retry must retain the original payment's inputs, recipient and amount, with normal fee approval and spend authentication.

1. Prepare a software-wallet payment and record the candidate transaction ID and exact mining fee. Allow the original broadcast to return Accepted while withholding transaction-history details. Verify local activity records that candidate's exact fee and the original Paykit proof can finish without another send.
2. Interrupt the app after the receipt is persisted. Verify restart retains the fee but does not finish an Unknown payment until the existing exact-transaction evidence identifies the winner. Separately interrupt after replacement acceptance but before local follow-up; reopen with history still withheld and verify the accepted winner's exact fee in Sent details, without another broadcast.
3. Authorize a same-input replacement with a different exact fee. Verify completion uses the actual winner's fee, including when the older candidate wins, and never borrows the other candidate's fee. Confirm the winning transaction and original inputs/recipient amount against the backend; local activity alone is not proof.
4. Fail the prepared-receipt save before dispatch. Verify no broadcast occurs. Separately replay an identical wallet backup over the same local operation and verify retained fees survive; altered operation, candidate or progress data must not overwrite it. Switch wallet or payer identity during retry preparation/authentication and verify the original identity and duplicate-payment guards still apply.

If the winning fee is not already known, an attempt without a retained candidate fee must still wait for verified transaction-history data. The private candidate-fee map is not exported in the shared backup: reset or a new installation uses the existing history fallback, not an imported fee. A retained fee is bookkeeping evidence, never payment-acceptance evidence.

## Platform adaptations

Android retains candidate fees for original sends and replacements in `candidateMiningFees`. iOS already retains the original prepared fee; `candidateFeeSats` covers successors/replacements. Run the same four cases on both platforms. Use each platform's normal identity-selection flow; iOS Max recovery keeps the original rate without an editable field. The common UI actions are in [onchain-original-payment-retry.xml](onchain-original-payment-retry.xml).

Record the platform, exact build, fixture/fault controls, observed results and untested cases separately. Injected Pending state is not proof of a native broadcast fault, and one platform's results do not establish the other's. These checks do not authorize changing acceptance, safety waits, backup formats or proof delivery semantics.
