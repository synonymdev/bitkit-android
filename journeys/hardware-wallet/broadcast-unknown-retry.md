# Hardware wallet retry after an unknown broadcast

These checks cover an ordinary hardware-wallet on-chain send whose broadcast outcome is unknown. Rebroadcasting the same signed transaction cannot pay twice because the txid stays the same, while a new signature can create a second payment. Broadcast faults are not in the journey Capabilities table, so this is a manual recipe. The same file applies to iOS and Android.

## Setup

- Use a disposable regtest wallet paired with the Bridge emulator and funded for several sends. Follow [the suite setup](README.md#prerequisites). Never use an existing wallet or real funds.
- Run a local Electrum JSON-RPC proxy between the app and the regtest Electrum server, and point the app's Electrum server setting at it. No such proxy is in this repo, so supply your own. It forwards every method unchanged, except that for `blockchain.transaction.broadcast` it can switch between pass-through and these faults: forward then return an error, swallow then return an error, hang without replying, return a given refusal, and refuse connections.
- Have the proxy log each broadcast's raw transaction, so that two attempts can be compared and the txid read with `decoderawtransaction`.
- Use `getrawmempool` on the regtest node as the record of what reached the network. Use the emulator's own screen and controller to see whether a device prompt appeared. Do not rely on local activity as proof of either.
- Mine with the suite's usual command between checks, so that each check starts with an empty mempool and no retained send. Forward-then-error makes the transaction visible, so a wallet sync can release the record before you act. Retry straight away, and rerun the check if the transaction shows in activity first.

Fee rate and tags do not decide whether two payments match; only the wallet, address and amount do.

## Checks

Unless a step says otherwise, the first attempt is a normal send approved on the emulator, and the sign screen's button reads Retry after a failure. Set pass-through before a retry that should succeed.

1. **Retry uses the same transaction.** Send with forward-then-error, then set pass-through and tap Retry. Expected: a "Transaction may have been sent" toast after the first attempt, no device prompt on retry, the same raw transaction and txid in both proxy log entries, Success, and one transaction in `getrawmempool`.
2. **Leaving and reopening keeps Retry.** Send with swallow-then-error, leave the send sheet, and start again to the same address and amount. Change the fee preset first if one is offered. Expected: the review shows the original fee and the fee cannot be edited, the sign button reads Retry, and after pass-through the retry succeeds with no device prompt and the original txid.
3. **A different amount warns first.** Send with swallow-then-error, leave, and start again to the same address with a different fundable amount. Repeat once with a different address. Expected: swiping to send on the review shows the "Transaction may have been sent" alert with Cancel and Yes, Send before any device prompt. Cancel stays on the review, with no device prompt and the record kept. Yes, Send continues to the sign screen, which signs anew on the emulator with a new txid.
4. **Force quit keeps the record until observed.** Send with swallow-then-error, force quit, relaunch, and start again to the same address and amount. Expected: Retry with the original fee, and no device prompt. Then send a fresh payment with forward-then-error, force quit, relaunch, and wait for the wallet to sync. Expected: once the transaction shows in the hardware wallet's activity, the record is gone, so the same address and amount no longer show Retry.
5. **A hang does not lock navigation after the timeout.** Send with the hang fault and wait more than 120 s. Expected: back navigation and sheet dismissal are ignored while the sign screen is still waiting, then the "Transaction may have been sent" toast at the timeout, and both work after it. Go back, set pass-through, and retry the same payment. Expected: no device prompt, the original txid and one mempool transaction.
6. **Refused connections do not block.** Set refuse-connections and send. Expected: the connectivity toast, and the app stays usable with back navigation working. Retry in the same sheet after pass-through broadcasts the same bytes with no device prompt. Separately, leave the sheet after the failure: a new send to the same payment asks for the device again, because nothing reached the network.
7. **A first-attempt refusal releases.** Return the refusal `min relay fee not met` on the first attempt. Expected: a failure toast, and the next attempt prompts on the emulator and produces a new signature, with no retained-send warning.
8. **A retry refusal warns and does not re-sign.** Send with swallow-then-error, then tap Retry with the refusal `bad-txns-inputs-missingorspent`. Expected: the "Transaction may have been sent" toast and no device prompt. Leaving and returning to the same payment shows the alert from check 3 before any device prompt, because a refusal on a retry may describe our own earlier accepted transaction. Only Yes, Send signs anew.

## What the Bridge emulator cannot prove

The emulator reaches the app through Trezor Bridge on the host. These runs say nothing about USB, BLE, THP pairing, Jade, or real hardware. Report those as untested. Unit tests cover the retained-send outcomes for each error, but injected state is not proof of a native broadcast fault.

Record the platform, exact build, proxy faults used, observed results and untested cases separately. One platform's results do not establish the other's.
