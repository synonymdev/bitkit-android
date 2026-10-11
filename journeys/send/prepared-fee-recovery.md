# Prepared transaction fee recovery

These checks need a disposable funded regtest wallet and a controlled native transaction-history or persistence fault. The journey capabilities do not provide those faults; ordinary payment success alone does not cover them. Never use an existing wallet or repeat a send after an unknown outcome.

1. Prepare a software-wallet payment and record the candidate transaction ID and exact mining fee. Allow the original broadcast to return Accepted while withholding transaction-history details. Verify local activity records that candidate's exact fee and the original Paykit proof can finish without another send.
2. Interrupt the app after the receipt is persisted. Verify restart retains the fee but does not finish an Unknown payment until the existing exact-transaction evidence identifies the winner.
3. Authorize a same-input replacement with a different exact fee. Verify completion uses the actual winner's fee, including when the older candidate wins, and never borrows the other candidate's fee.
4. Fail the prepared-receipt save before dispatch. Verify no broadcast occurs. Separately replay the existing wallet backup and switch wallet or payer identity; verify the original identity and duplicate-payment guards still apply.

If the winning fee is not already known, an attempt without a retained candidate fee must still wait for verified transaction-history data. These checks do not authorize changing acceptance, safety waits, backup formats, or proof delivery semantics.
