# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [3.2.0] - 2026-11-27

### Added
- Spending keeps Lightning without channels: transfers from Savings now fund a Spark wallet that sends and receives Lightning payments, with an emergency exit that moves its balance on-chain without the Spark operators. #1697
- Paykit contacts and payment requests can be paid from Spark, including contacts and requests that only accept on-chain bitcoin. #1754
- Move to Spark: move a Lightning connection's balance to Spark, then close the emptied connection to return its reserve to Savings. #1768
- When a Paykit payment request you sent is paid, Bitkit now sends the payer a receipt and shows whether it was sent. #1789
- Moving funds to Spark now shows as a Move to Spark entry in Activity and keeps going after you leave its screen. #1819
- App Status shows Spark's connection, and Lightning Connection no longer reports an error while Spark carries Lightning payments. #1831
- App Status shows an emergency exit while it runs. #1832
- Pay by holding your phone near a payment terminal or another phone over NFC, and send or receive with nearby Bitkit wallets over Bluetooth. #1883

### Changed
- Contact payments are now always on while you have a profile, and their two toggles are gone from Settings. #1932
- The terms of use now cover Pubky and Paykit in place of Slashtags, with an effective date of November 27, 2026. #1936
- Moving the spending balance to Spark now runs from one Update modal, closes the channels it empties and lets a Lightning payment combine both balances with one swipe; the CJIT receive card is removed. #1946
- Supported pubky.app profiles moved to the new social format, and showed each sent receipt as sent or failed from its actual delivery status. #1955
- Redesigned Transfer to Savings and the Spark force transfer, which can now be paid from another wallet and skips the availability warning when only Spark funds move. #1960
- Transfers to spending show the new fee breakdown with Fast and Normal speeds, a clearer Learn More, and an updated hardware wallet signing screen. #1963
- Spark connection details and App Status show Spark's status, provider, balance and deposits, and a force transfer to savings on the Spark row. #1964
- Moving the spending balance to Spark now takes one swipe for the whole balance, splitting it into smaller payments when needed. #1984

### Fixed
- Fixed max on-chain sends so the amount always matches the fee speed and recipient it is sent at. #1144
- Fixed incorrect confirmation estimates for transactions boosted using Replace-by-Fee. #1429
- Fixed Bitcoin payment URI validation and prevented automatic clipboard inspection from contacting payment endpoints before confirmation. #1454
- Fixed the Pubky authorization screen not showing which relay receives the authorization. #1505
- Fixed a rare crash when the Lightning node stops while background work is still running. #1536
- The contact import overview now reads "1 friend" for a single contact and uses each language's plural forms. #1601
- Fixed Bitcoin payments from Savings being sent with a higher network fee than the one shown on the confirmation: Bitkit now shows the actual fee and asks you to confirm again before sending. #1657
- Fixed the contact Request or Pay sheet hiding its Pay and Request buttons on small screens and with large text. #1666
- Show ordinary Lightning payment results without waiting for Paykit maintenance, preserve valid allowance acceptance during contact sync, and keep allowances stopped after switch-off or profile removal. #1684
- Fixed a crash when the system blocks turning on Bluetooth while connecting a hardware wallet. #1694
- Payment request amount entry keeps the number pad above Continue, and the Payment Requested sheet keeps OK at the bottom with the recipient above it. #1705
- The Payment Request recipient picker now shows a loading row while it checks which contacts can receive a request, and says when that check failed instead of saying no contact can receive one. #1742
- Fixed paid payment requests showing as payable again and restored wallets missing recent automatic payments, and sped up automatic payments. #1751
- Lightning Connections now keeps showing your connections while the Lightning node restarts, with a syncing indicator, instead of a full-screen connecting view. #1756
- Fixed Pubky sign-in requests meant for another pubky being signed; Bitkit now refuses them and names the pubky they are for. #1761
- Scanning a Bitcoin or USDT payment request that already has an amount now opens the review screen directly instead of the amount screen. #1763
- Hardware wallet sends now keep the signed transaction after an uncertain broadcast, so retrying cannot create a second payment without a warning. #1770
- Payments to saved contacts that can be paid privately always stay private, and Bitkit explains that you both need to add each other as contacts while the private connection is set up. #1778
- Automatic allowance payments now stay paused after a wallet restore until you resume them, and requests under an allowance with limits Bitkit does not check itself ask for your approval. #1779
- Made payment requests funded from a hardware wallet ready to confirm sooner by composing the payment once. #1785
- Show an insufficient funds message when a scanned Bitcoin request is more than a hardware wallet can pay after fees, instead of opening an amount screen that cannot continue. #1790
- Bitkit no longer offers the pubky.app moderation account as a contact when importing a Pubky profile. #1794
- Show the USDT received celebration for a receipt that confirms while the wallet is still syncing its history after launch or restore. #1799
- Stopped re-uploading the activity backup after every wallet sync when nothing changed. #1800
- Refresh the Lightning spending limit after receiving a payment so Send rejects amounts above the available balance immediately. #1808
- Boosted Bitcoin sends now open the replacement transaction with the correct fee and payment history. #1813
- Pending fee boosts keep their approved fee when you reopen the transaction and show feedback while replacement details load. #1821
- Contact payment errors in toasts show a readable message instead of raw SDK details. #1851
- Swiping back from a screen slides it out like the Back button does, and other apps can no longer open Bitkit's internal screens. #1853
- Fixed force transfer progress offering no way to pay the emergency fees from savings after their fee rose before the payment went out. #1856
- App Status no longer shows Lightning Connection as using Spark while an emergency exit runs. #1880
- Fixed profile links that pubky.app would refuse blocking the profile save: the link sheet now shows what to fix before saving, and Edit Profile points to an existing link that needs fixing. #1882
- Restoring a Pubky profile preserves its saved tags when the profile initially loads without them. #1884
- Funds in transfer to spending through Spark follows the new design, and the Home transfer and Savings banners open the transfer or emergency exit in progress. #1901
- QuickPay now asks for confirmation instead of paying automatically when an invoice is opened again after QuickPay was turned off. #1902
- A fast transfer to spending keeps its speed, its changed-fee prompt and its early claim after the app is reopened, and the transfer banner opens it at once. #1905
- Fixed contact payment requests with a Lightning invoice from a public node failing to open when the invoice carried no route hints. #1911
- Profile edits save sooner while Bitkit updates contact payment details in the background. #1945
- Profile edits save faster when payment details sync in the background. #1951
- Payments now stop if QuickPay's wallet lock lapses, or a payment request's contact is blocked, while the send is being prepared, on Lightning and on-chain. #1962
- An edited Receive amount and note now stay on the request when Bitkit returns from the background. #1971
- Fixed the transfer to savings staying in progress forever after a force close that left only dust, and showing the channel size instead of your balance. #1973
- Fixed wallet restores reporting Spark exit data as restored when Spark rejects it. #1976
- Updating the spending balance now quotes its fee more closely, no longer reports success with a balance left or shows failed rows for parts it retried, and no longer fails a payment started during the update. #1989
- Wallets that still used a retired Bitkit Electrum server, such as fulcrum.bitkit.blocktank.to, now switch to the current default, so the node starts again without resetting the server by hand. #1993
- Fixed a graphics crash on some Android devices while the camera is open for scanning. #1994
- Fixed a rare case where restarting the Lightning node could overwrite its newer backed-up state with older data. #2004
- Fixed connecting Bitkit to Paykit Server failing with "The wallet must be running" right after returning from the browser. #2006
- Fixed Spark payment retries so a retry stays pending until its own outcome, and a payment settled while the app was closed no longer shows its result again at launch. #2007
- Fixed the Lightning node starting again after it was stopped while recovering from a failed server change. #2012
- Fixed rare cases where a Lightning payment retried over Spark could be sent twice or show a stale outcome. #2014
- Fixed rare cases where a Spark payment outcome could be missed after a storage error, or a retry of an invoice from before a restore was refused. #2015
- Keep the payment confirm screen open while a swipe is paying, so leaving can't undo it silently. #2017
- Fixed a Lightning invoice paid through Spark being paid twice when the app retried it after an interrupted send, and a payment made right after moving funds to Spark staying pending. #2019
- Bitkit now asks you to update the app when a profile was saved by a newer version, instead of showing a raw error. #2022
- The "Payment returned to Savings" notice now stays on screen until you close it. #2030
- Fixed a camera or pasted scan on the Send screen opening its payment after you tapped Enter Manually. #2035
- The custom fee screen now shows the current fee as selected, so typing replaces it instead of adding digits to it. #2038
- The LNURL sign-in sheet now says whether a site asks you to log in, register, link your account or approve an action, instead of always asking to log in. #2039
- Copying on the Receive screen now gives haptic feedback on Android. #2040
- Fixed the send confirmation staying on its spinner when paying an invoice that was already paid. #2048
- Fixed the wrong PIN message on the Found Jade step disappearing when the next scan found the same Jade. #2049
- Fixed a Jade logged out on the device showing as busy: it now asks for its PIN and carries on. #2050
- Fixed force transfer progress asking for the emergency fees while a payment of them from savings could still confirm. #2059
- Lightning payments and withdrawals through Spark refused before sending now show why, even when clearing the attempt fails. #2060
- Fixed the payment receipt shown when paying again an invoice that was already paid. #2061
- A repeat of an invoice at another amount than its earlier Spark payment is refused, and a repeat of a pending payment shows the amount that payment sends. #2071
- Fixed the round action buttons on the contact and profile screens to show a soft top highlight instead of a full ring, and drew the contact Activity icon at its designed weight. #2119
- Fixed layouts that stretched, overlapped or were cut off on tablets, including the profile QR code, home suggestion cards, transfer intro screens and the drawer menu. #2127
- Fixed a force transfer asking for its fees again, allowing a cancel, or paying them again from savings while a payment of them could still land or had already arrived. #2131
- Fixed a savings payment a force transfer no longer needs blocking new on-chain sends; Send Pending now offers to cancel it instead of paying it again. #2136
- Fixed connecting a Jade with no wallet showing a generic message instead of how to set it up. #2144
- Fixed a valid Electrum or RGS server being rejected while the Lightning node was still saving its backup. #2147
- Fixed the force transfer's fee request staying hidden or too large after a savings payment that never went out or failed, and a fee reminder sent while funding already covered the fees. #2151

### Security
- Update BouncyCastle to 1.86, which fixes certificate-validation and ASN.1 parsing vulnerabilities in the crypto provider the app installs. #1787
- QuickPay now asks for your payment PIN before it can be turned on or its limits raised. #1909







## [3.1.1] - 2026-11-12

### Added
- Bitkit now uses your pubky.app profile, so edits in Bitkit and on pubky.app show in both. #1801

### Fixed
- Fixed an occasional app freeze on launch while Pubky contacts start up. #1798
- Paykit payment requests now check that a payment proof fits before paying, explain notes that are too long and refused signup codes, and no longer count a USDT transfer sent before its request as payment. #1803

### Security
- Updated the Lightning node with upstream security fixes. #1870
- Updated the VSS client and Paykit with upstream rustls security fixes. #1824, #1873

## [3.1.0] - 2026-11-02

### Added
- Allowances: set a per-payment and a monthly limit for a Paykit contact so their payment requests within the limits are paid automatically. #1340
- Allowances now activate automatically on the receiving wallet: when a contact sets up an allowance for you, Bitkit accepts it and lets you know. #1385
- Bitkit now shows a toast when a pending payment request expires. #1624
- The send confirmation now estimates the confirmation time for a custom on-chain fee from the current fee tiers. #1634
- On-chain sends without Lightning channels now show a bitcoin-themed Connecting to network view. #1718
- Allowances can be turned off from Dev Settings. #1734

### Changed
- The wallet picker on the send amount screen now shows a dropdown arrow instead of the switch icon. #1612
- Updated the terms of use to cover USDT support and third-party services. #1613
- Payment requests from a shop now show the payee under To instead of Contact. #1625
- Hardware wallet screens now show the current Jade Plus and Trezor Safe 7 devices. #1665
- Pubkys from Pubky Ring now show "Unlink pubky" instead of "Delete Profile" in Edit Profile, making clear the pubky stays in Pubky Ring. #1677
- Blocktank no longer sells Lightning connections, so transfers to spending and receiving beyond your spending capacity now point to Manual Setup, and a forced close of a Blocktank connection no longer leaves funds stuck. #1696

### Fixed
- Improved USDT payment layout, contact loading feedback and receive sharing amounts. #1523
- Keep Bitcoin and Lightning receiving details up to date when USDT address preparation is temporarily unavailable. #1524
- Improved USDT scanning, amount formatting, bridge errors, refund feedback, accessibility and payment retry handling. #1531
- Fixed paid Paykit payment requests briefly reappearing as payable after sending, kept paid USDT requests hidden while their payment proofs are delivered, and refreshed retryable payments promptly. #1534
- Avoid an unnecessary registry update during profile deletion while preserving pending cleanup and retries. #1538
- Eligible initial USDT subscription payments continue after subscribing without a second swipe while retaining authentication and safety checks, with retryable unpaid periods, expired requests retained in payment history, and only paid periods shown in subscription details. #1540
- Keep USDT wallet transitions and Receive controls consistent, improve payment request and subscription amount layouts, and keep compact QR codes scannable. #1565
- The Forgot PIN and unavailable subscription sheets now keep their buttons above the navigation bar. #1572
- Optional reminders no longer interrupt the QR scanner or other open sheets and remain available for a later Home visit. #1575
- The Bitcoin Headlines widget keeps its time on one line at large font sizes. #1576
- System Back on the payment PIN screen now cancels a retry of the original payment, so a later retry works without restarting the app. #1577
- Retry original payment now uses Bitkit's dialog style, with a labelled recipient, the usual amount block and a fee field showing its unit. #1578
- Toasts with a close button keep their text clear of the button in every language. #1579
- Show the USDT introduction after interrupted startup and remember when it has been seen. #1580
- Fixed quoted Bitcoin payment requests opening an unnecessary amount-entry screen before confirmation. #1581
- Swiping over the USDT receive QR now changes tabs instead of copying the address. #1582
- Receiving USDT now slides between the address, network, amount and fee screens, like the Bitcoin receive flow. #1586
- Reduce delays while authorizing Paykit payments and delivering payment confirmations. #1591
- Swiping past the first or last Receive tab now wraps around to the tab at the other end. #1593
- Updated USDT bridge validation and recovery, with clearer errors when deposit liquidity is unavailable. #1600
- Swiping past the first or last Receive tab now slides to the other end like the other tabs. #1608
- Bitkit now shows Paykit again after updating on wallets where it had been turned off. #1618
- Show a network reminder when copying a USDT receive address. #1619
- Fixed payment requests with a set amount moving to the Amount step on their own; they now open on confirmation, where Pay From switches between bitcoin and USDT and an expired USDT fee quote refreshes in place. #1623
- Defer automatic Paykit contact updates during payment review and sending, and retry them afterward. #1626
- The USDT amount keypad now sits right above Continue, as on the Bitcoin amount screen. #1627
- On the expanded USDT payment review, the fee note no longer crowds Hide Details, and long details scroll above Swipe To Pay, which stays clear of the navigation bar. #1628
- Tapping the transaction ID in USDT activity details now copies it. #1633
- Background payment-request updates now give priority to payment review and submission. #1639
- USDT sends over half your USDT balance now warn about the USDT balance instead of the total balance. #1644
- Improve how incoming payment requests refresh without interrupting payment review. #1645
- Use a single BTC/USD denomination toggle when creating payment requests and subscriptions. #1654
- Show USDT balances quietly with consistent decimals, a currency symbol on Home and an unknown marker until the balance loads. #1655
- Incoming Lightning payments can use the whole channel instead of 10% of it, on channels opened after this update. #1662
- Scale received-payment artwork consistently across Bitcoin, Lightning and USDT sheets. #1664
- Payment requests blocked by an earlier on-chain payment can be retried after that payment is resolved. #1676
- Coalesce simultaneous activity refreshes and preserve an active refresh when another caller times out. #1680
- Keep ordinary USDT recovery backups independent of Pubky and resume the same signed payment after backup failures. #1681
- Show locally available transaction details for restored payments while keeping background synchronization limited to cached details. #1682
- Show contact profiles and allow saving while optional payment details are still loading. #1683
- Keep wallet actions gated and recovery visible during required downloads, allow connection corrections, and avoid repeated node restarts while backup recovery retries. #1685
- Show the last known Home balance while USDT balances and exchange rates refresh. #1700
- Use the saved Bitcoin fee schedule during temporary gateway errors while keeping payment links and requests blocked until required wallet recovery finishes. #1701
- Creating a Pubky profile no longer waits for a redundant profile read or contact refresh after saving. #1711
- Payment requests and subscription payments now open confirmation with the requested amount and let you choose a payment source from the send dropdown. #1713
- On-chain sends now pay the fee rate shown on the confirm screen. #1722
- Keep payment entry responsive while checking wallet recovery. #1725
- Home marks the balance with a soft shimmer while it updates, without moving the screen. #1729
- Restoring a backup made on iOS now brings back your profile, payment history, contact labels and settings, and the restore screen names any data it could not restore. #1733
- Automatic allowance payments also check spending, paid requests and ended allowances recorded in the wallet and its backup, and pause with a Resume option when allowance records are older than ones the wallet already used. #1738
- Bitkit shows the updated Tether artwork and USDT introduction, colours send reviews by the paying wallet, and always shows Request Payment, opening Add Contact when you have no contacts. #1741
- Boost only replaces outgoing transactions this wallet sent. #1706

### Security
- Keep contact payments private by default and require explicit opt-in to public payments with separately reserved Bitcoin addresses. #1668







## [3.0.1] - 2026-10-19

### Fixed
- Pause automatic Paykit refresh and publication during profile deletion while preserving safe cleanup and recovery. #1510
- Fixed Pubky sign-in links closing Bitkit or doing nothing while it starts; Bitkit now shows that it is loading your profile until the request opens. #1514
- On-chain payments are sent again after a dropped Electrum connection instead of failing until the app restarts. #1526
- Preserved pending USDT payment protection after backup restore and kept bridge support details safely formatted. #1528
- Pubky sign-in links that open Bitkit now show the Authorize sheet even while your Pubky profile is still loading. #1541
- Opening another link while a Pubky sign-in is pending no longer drops the sign-in; the other link opens after it. #1542
- Wallet backups that run while Paykit is busy now retry on their own instead of waiting for the next change or restart. #1550
- An on-chain payment whose request ended before it reached the network can now be cancelled, which sends its coins back to the wallet so it no longer blocks new on-chain payments. #1551
- Copy plain USDT receiving addresses and share them with the selected network, requested amount and payment QR code. #1558

## [3.0.0] - 2026-10-16

### Added
- Bitkit now shows helpful guidance the first time you switch the displayed balance unit or swipe to hide your wallet balance. #1272
- Added support for creating a Pubky identity from app-authorized and direct Pubky signup requests. #1224
- Added support for pairing Blockstream Jade and Jade Plus hardware wallets over USB and Bluetooth, including watch-only balances, address verification and on-device transaction signing. #1231
- Bitkit can now propose recurring subscriptions to Paykit contacts. #1239
- Wallet recovery now supports scanning 12-word Standard and Compact SeedQR backups. #1319
- Open Pubky contact links directly in Bitkit to add a contact or view an existing one. #1320
- Bitkit can now use a pubky you already keep in Pubky Ring, and shares its own pubky with Pubky Ring. #1329
- Bitkit can move subscription renewals forward from Dev Settings for testing. #1341
- Bitkit can now hide all toasts from Dev Settings. #1371
- Opt in to USDT in Settings to send and receive with fees paid in USDT, Paykit contact payments and requests, subscriptions, cross-network deposits, optional Earn address sharing, and encrypted payment recovery backups. #1432
- Support Pubky grant signup links through the existing authorization and profile setup flows. #1446
- Added missing translations, including profile, contacts, subscription and USDT screens, across languages. #1492

### Changed
- Standardized intro sheets with consistent artwork, spacing, actions, and localized messaging. #1314
- Incoming payment requests are checked every ten seconds while the app is open and online. #1321
- Incoming Payment Requests now show who the request is from and what it is for under the amount. #1336
- Paykit contacts, payment requests and subscriptions are now on by default. #1345
- Incoming Payment Requests without a note now show "Not specified" under For. #1358
- Share Paykit contacts and payment state with authorized apps while keeping wallet keys private, restore contacts when re-importing an identity, prepare incoming payments in the payment sheet, support one-time requests with absolute payment deadlines, prioritize payment submission over background synchronization, distinguish temporary restoration failures from expired sessions, preserve subscription reminders through retries, and label received payments with their Paykit contact. #1401
- Pubky authorization dialogs now name the requesting service, group permissions and details under clear headings, and show a compact profile card. #1421
- Accepting a subscription now sends the first payment in the same swipe when it is paid on-chain, as it already did over Lightning. #1422
- Jade hardware wallets now display device artwork that matches the app's hardware wallet design. #1427
- Refine Earn and USDT payment layouts, improve large-text controls and localized amount entry, let requests and subscriptions choose individual receiving methods, and keep manual entry and paste available when a camera cannot start. #1494
- Pulling to refresh on Home now also refreshes exchange rates. #622
- Bitkit now asks for your PIN again whenever you return to the app from the background, and a payment link opened while it was locked now takes you to the send confirmation once you unlock, instead of paying automatically. #724

### Fixed
- Preserve payment guards through sign-out, retain USDT receive notifications, and correct payment-request navigation, styling and bridge status messages. #1506
- Keep wallet backups progressing during Paykit activity without accepting inconsistent payment snapshots. #1507
- Retried Lightning payments now show the amount and fee of the attempt that succeeded. #1042
- An expired Paykit session is now reported on startup when the profile service fails to start. #1305
- Retrying a failed wallet restore no longer leaves the Lightning node in an error state after a temporary startup failure. #1291
- Contact activity no longer hides hardware wallet payments that share a transaction ID with a boosted payment in another wallet. #1139
- Restoring a wallet now keeps its activity tags and closed connections: a single tag that cannot be applied no longer discards the rest of the backup, and ordinary backups are held until the restore has finished so they cannot overwrite it first. #1171
- Connecting to a custom Electrum server now explains when it is on the wrong Bitcoin network, when the TCP/TLS protocol does not match the port, or when its TLS certificate is untrusted or issued for another host. #1177
- Incoming Payment Requests now report safe failure reasons and show an error when an opened request cannot be resolved. #1217
- The transfer to spending flow now creates the Blocktank order only when you confirm, instead of on every Continue tap. #1247
- Incoming payment requests appear sooner while the app is open, without unnecessary wallet backup uploads. #1255
- Refreshed the Subscriptions screens to match their designs, including blurred top and bottom bars the list now scrolls behind. #1269
- Polished wallet, transfer, receive, onboarding, and settings screens with corrected spacing, typography, icons, and amount displays. #1270
- Improved Pubky identity discovery by periodically refreshing existing identity records while Bitkit is open. #1271
- Fixed toasts being obscured or dimmed by modal sheets. #1273
- The mandatory update screen now also appears before a wallet is created or restored. #1295
- Transfers to spending no longer stay in progress after their Lightning channel was closed. #1303
- Fixed the Receive sheet sliding to the Auto tab on open and overriding a tab you had already picked. #1308
- The Report Issue message field now stays above the keyboard while typing multiple lines. #1322
- Spending confirm now shows the amount of bitcoin that leaves the wallet when a savings transfer uses send-all. #1326
- The backup reveal overlay no longer exposes the recovery phrase through accessibility text. #1327
- Sensitive screens keep screenshots and recent-app previews blocked until the last one closes. #1328
- The Create Profile, Edit Profile and Profile screens now match the design, with the edit form scrolling under the Cancel and Save buttons and tags managed from Edit Profile. #1332
- Preserve Paykit subscription and pending payment state in wallet backups, and retain saved data when it cannot be read or restored. #1334
- Payout authorization no longer fails when the initial wallet sync times out. #1338
- Fixed Paykit recovery after connection failures and device clock changes to preserve profile data and contacts, restore saved sessions, and improve billing reminder timing. #1339
- A historical on-chain payment no longer shows a Received sheet after restoring a wallet from its recovery phrase. #1342
- Paying a contact now always offers Request or Pay when the contact accepts requests, and opens the amount screen in about a second with a loading indicator. #1349
- Backing out of Pubky Choice while picking a Ring pubky, or a failed sign-in, no longer leaves a hidden signed-in session behind. #1363
- Keep Paykit requests with unsupported payment deadlines in history and explain why their subscriptions cannot be accepted. #1365
- Fixed private payment requests remaining unavailable after safe send failures, blocking actions during link recovery, and displaying unpaid requests as completed payments. #1370
- Deleting a contact now stops private payment requests until you add that contact again, and background refreshes no longer restore deleted contacts. #1372
- Fixed wallet setup getting stuck at 100% after retrying a failed startup. #1373
- On-chain sends keep uncertain payments protected across restart and wallet restore, with an authenticated retry of the original amount and inputs. #1384
- Send no longer stays on Connecting to network when a Lightning channel becomes usable again after its peer reconnects. #1390
- A canceled subscription now stays active until its paid period ends, then expires. #1391
- An interrupted wallet sync, such as one cut off when the app closes, no longer turns the app status red. #1392
- Fixed profile recovery and disconnection after connection loss and added visible progress while updating or deleting a profile. #1394
- Fixed slow contact imports by saving prepared contacts without repeating remote lookups, excluding your own profile and keeping failed imports available for retry. #1395
- Restoring a React Native wallet waits for its Lightning channel backup, and activity tags that are not ready yet are kept for a later sync. #1396
- Pubky profiles and contacts load faster: Pubky Ring identities and saved contacts appear at once, avatars are cached between launches, the Profile screen shows your cached name and avatar while it loads, a contact import no longer stops when you leave its screen, and an authorization link that opens Bitkit waits for your Pubky profile to reconnect instead of asking you to create one. #1399
- Fixed Paykit signup and authorization failures after wiping a wallet. #1400
- New incoming Payment Requests open automatically while Bitkit is unlocked. The payment amount and confirmation slider stay visible while details scroll. #1405
- Pubky profile, contact and payment features no longer fail after a cold start until the app is restarted. #1416
- Contact tag changes and edits still waiting to save when you sign out of Pubky are now dropped, instead of saving to the profile signed in next. #1417
- Keep contact linking retries independent and prioritize contacts added or refreshed while Bitkit is active. #1451
- Fixed a contact that was removed and added again showing its outdated profile. #1434
- Pause scheduled contact preparation while Bitkit is backgrounded and preserve background priority for contact retries. #1436
- Pay fixed-price Paykit requests in Bitcoin using the issuer's quoted amount and stop preparation with feedback when the destination is not payable. #1437
- Keep the saved public profile readable while its Paykit session is restoring. #1443
- Contact payment setup now reports temporary access failures instead of silently disabling private payments. #1444
- Fixed manual coin selection being skipped before confirming a Savings payment. #1450
- Fixed contact readiness retries stopping after an unavailable contact reconnects. #1455
- Payment requests below the minimum Bitcoin amount now show the minimum and close payment preparation. #1457
- Completed Paykit payments no longer wait for proof delivery after their payment details have been safely saved. #1458
- Fixed on-chain payment completion waiting for transaction history when its exact prepared fee is already available. #1459
- Keep a single Bitcoin send in activity history as it confirms, preserving its contact and fee. #1460
- Fixed missing fee estimates when reviewing Lightning invoices with a set amount. #1461
- Unresolved metadata from React Native wallets stays saved for later recovery while retries slow down across app restarts. #1462
- Kept the subscription review sheet open when starting the first payment. #1469
- Received on-chain payments now show as confirmed once their block is mined, even when the block's time is earlier than when Bitkit first saw the payment. #1477
- Fixed force-closed channels failing to claim pending payments on-chain. #1480
- Receiving Bitcoin now keeps the latest invoice in the Auto QR code during overlapping refreshes and lets you retry failed invoice updates. #1483
- Discover Shop sellers from buyer interaction markers on app resume and payment-request polls, preserving edited and removed contacts, and refresh shared contacts before opening contact links. #1485
- Improve Pubky file handling and keep sensitive document values out of Paykit error messages. #1500
- Comments added when paying an LNURL-pay request are now saved as the payment note in the activity. #417
- Fixed the sats amount keypad blocking extra digits and ignoring delete presses after using a preset amount or switching units. #558
- Coin selection now retries while the Lightning node is starting and shows an inline error with a retry button when your coins cannot be loaded. #616
- Tags are now limited to 20 characters on a single line, long tags keep their remove button visible, and submitting a blank tag field no longer saves an empty tag. #630
- Fixed the activity date range calendar breaking when switching months quickly. #631
- Long recovery phrase words now shrink to fit on one line instead of wrapping. #633
- A wrong word in the recovery phrase confirmation can now be cleared by tapping the red word or its button, including the last word. #634
- Fixed the PIN setup result screen toggle overlapping long translated labels. #638
- Shop gift card category names can now be translated. #715
- Show the received transaction sheet or notification for on-chain deposits first seen already confirmed. #797
- Tapping OK after moving funds from spending to savings now returns to the home screen instead of the spending wallet. #809
- Lightning payments no longer stay unavailable after the node startup is interrupted, such as by leaving the app while it starts. #845
- Fixed the sync status flashing after wiping a wallet migrated from the previous Bitkit app. #869
- Removed the redundant Auto toggle from the manual coin selection screen. #894
- Pasting part of a recovery phrase on the restore screen now fills the following word fields instead of being ignored. #896
- Paying a Lightning invoice created by your own wallet now shows an error, and unified QR codes containing one fall back to an on-chain send. #901
- Receiving over Lightning now refreshes liquidity requests correctly and avoids showing stale or invalid CJIT quotes.

### Security
- Paykit payment approvals remain tied to the reviewed proposal when private state changes. #1470
- Updated the Lightning node with upstream security fixes. #1475

## [2.5.1] - 2026-10-13

### Fixed
- Updated LDK Node to 0.7.0-rc.66.1 for the upstream Lightning security fixes. #1472

## [2.5.0] - 2026-09-10

### Added
- Added receiver-specific Paykit contact support for Bitkit wallet and server integrations. #1066
- Bitkit now creates, names, backs up, and manages separate watch-only Bitcoin accounts and securely delivers signed setup claims to Paykit servers. #1084
- Incoming Paykit payment requests can now be reviewed and approved through the existing payment flow. #1098
- Developers running their own build can now open supported screens directly with `bitkit://screen/...` links while dev mode is enabled. #1119
- Passphrase-protected (hidden) Trezor wallets can now be paired from the connect flow, each appearing as its own watch-only balance with its own label, activity and removal, and asking for its passphrase again when a transfer needs signing. #1142
- Incoming Paykit payment requests now stay discoverable until handled, and users can send new private requests to connected contacts from the invoice flow. #1172
- The name you give a hardware wallet is now included in your backup and comes back when you pair the device again, and removing a hardware wallet asks first whether to keep its name and tags in your backup. #1173
- Payments made from incoming private payment requests now send a payment proof back to the requester. #1178
- Bitkit can now review, manage, and pay recurring payment requests from Paykit contacts, with on-chain fees shown before confirming the first payment and unresolved payments protected from accidental retries. #1186
- Added on-chain send support for paired Trezor wallets, including transaction approval on the device. #1187
- Added a Trezor Receive tab with fast watch-only addresses and on-device verification. #1189

### Changed
- Added support for tags and transaction details to hardware wallet activity. #1044
- Updated Pubky profiles, contacts, and Paykit settings to match the latest designs and simplified contact payments. #1097
- Send now keeps the funding-source button, fee label, and swipe-to-confirm track stable while switching sources, continuing from amount, or refreshing fees. #1195

### Fixed
- Resetting the wallet no longer leaves backups broken after a failed setup, and no longer races an in-flight Lightning start. #1266
- Receive requests now refresh reliably after a payment is completed. #1086
- The Show Details button on the Receive screen is now available while the Lightning node is still starting up, so you can view and copy your on-chain address right away. #1090
- Savings transfers to spending now use a faster on-chain fee rate, reserve mining fees when sizing max transfers, and only drain the wallet when leftover change would be dust or a retry can still fully fund the order. #1091
- Fixed profile deletion being blocked when private Paykit contact cleanup could not complete. #1108
- Activity timestamps now follow the device's 12/24-hour time setting instead of always using a 12-hour clock. #1111
- App Status and Backup timestamps now follow the device language instead of always using the US date format. #1124
- Fixed Paykit Server authorization to require both public and private payment capabilities. #1131
- Restored the transfer label on hardware wallet transactions after removing and re-adding a device. #1133
- Improved Lightning send failure recovery with clearer messages and a retry action that refreshes payment routing. #1140
- Fixed delayed private payment requests after adding a Paykit contact or returning to the app. #1141
- Tags you add to hardware wallet activity are now included in your backup, and older backups are upgraded automatically the first time you restore them. #1163
- Error toasts are now visible on top of modal bottom sheets, including the hardware transfer passphrase sheet. #1165
- The maximum Savings to Spending transfer amount now always leaves room for its own service fee, so transferring your full balance no longer fails with an insufficient funds error. #1179
- The advanced transfer screen now offers a maximum receiving capacity your balance can actually pay for, instead of one that fails later on the confirmation screen. #1180
- Hardware-wallet payments now send payment proofs for incoming Paykit requests. #1199
- Receiving over Lightning now correctly falls back to Savings or additional liquidity setup when the requested amount exceeds available inbound capacity. #1222
- The app no longer aborts when stopping the Lightning node, and 32-bit devices can open the wallet again. #1223
- A hardware wallet payment whose broadcast fails no longer leaves the send screen and sheet stuck, and Bitkit now says the payment could not be confirmed instead of reporting a generic connection problem. #1230
- LSP orders now include a wallet-owned address for on-chain refunds. #1235

### Security
- QuickPay stays PIN-free under a configurable daily spend limit; once that limit is reached, payments open Confirm instead. #1159
- Connecting a custom Electrum server no longer crashes Bitkit if the server sends an oversized response. #1198
- Added app-scoped Pubky authorization and secure sign-out, and fixed missing payment requests in history. #1200

## [2.4.1] - 2026-08-21

### Changed
- Bitkit now keeps the Lightning node running a little longer when you briefly leave the app, so quick trips to another app no longer reload the wallet on return. #1146

### Fixed
- Lightning node teardown now releases native resources deterministically and no longer restarts the node when the app is only briefly backgrounded. #1100
- Fixed native library compatibility on Android devices using 16 KB memory pages. #1107
- Lightning node shutdown and peer persistence now complete without native crashes or app hangs. #1122
- Fixed Pubky authorization links opening Bitkit when the feature is unavailable or no local identity can approve them. #1162
- Fixed the wallet backup failing repeatedly when Paykit state could not be read. #1092
- Hardened background task error handling to prevent rare crashes. #1094

### Security
- Lightning no longer automatically starts from outdated channel monitor data after a storage mismatch. #1155
- Wallet backups no longer fall back to unauthenticated VSS when LNURL-auth is missing. #1156
- Shop checkout only accepts Bitrefill payment requests, and payment links wait until the wallet is unlocked. #1158
- Wallet backups now use VSS 0.5.23, which rejects unauthenticated encryption. #1164

## [2.4.0] - 2026-07-15

### Added
- Added a dedicated detail screen for paired hardware wallets, showing balances, transaction history, and a remove action. #1022
- Added a Hardware Wallets settings screen to view and remove paired devices, reachable from Settings under General › Payments. #1032
- Connect a Trezor hardware wallet from the home suggestion card or Hardware Wallets settings to watch its balance. #1033
- Transfer funds from a paired Trezor hardware wallet to your spending balance, with safer fee handling and confirmation tracking for device-signed funding transactions. #1039
- Background payments now include a "Keep Bitkit active in background" option for more reliable payments, and payment notifications always show the amount received. #1053
- Added the ability to rename saved hardware wallets from Settings. #1057
- Trezor hardware wallet support with improved connection reliability, native SegWit balance tracking, and transfer to spending. #1062
- Show paired Trezor hardware wallet balances and activity on the home screen, with sheets to enter pairing code and notify incoming hardware wallet transactions. #999

### Changed
- Update FX rates endpoint to no longer use old Blocktank service. #1072

### Fixed
- Hardware wallet transfers to spending now use a faster on-chain fee rate so funding confirms more reliably. #1089
- Connection Details now shows the short channel ID for Lightning connections instead of the long internal channel ID. #1002
- The system notification permission dialog is now only requested when you tap Enable on the background payments prompt, instead of appearing automatically on every app open. #1004
- Transfer to Spending now fills in the maximum transferable amount when you enter a value above the limit, instead of only showing an error. #1013
- Bitkit now handles more unexpected native on-chain wallet sync failures without crashing. #1015
- Re-adding the Suggestions widget now restores the default suggestion cards when all of them had been dismissed. #1016
- Transferring to your spending balance now reliably shows the "Spending Balance Ready" confirmation instead of sometimes being mistaken for an incoming payment when an unused instant-payment invoice is still pending. #1017
- Recovery mode now has a Reset Network Graph option that re-downloads the Lightning network graph to fix "route not found" errors. #1020
- Error toasts now use the brand orange color to match the design prototype. #1023
- Channel close and transfer activities, including force closes, now offer an Explore button so the on-chain transaction ID and block explorer details are accessible. #1037
- Spending balances no longer briefly include cooperatively closed channel funds after opening a new channel. #1043
- Tapping an NFC tag now opens the gift claim and other deep links instead of being ignored, and the NFC intent filter no longer declares the unused BROWSABLE category that triggered a Play Console deep link warning. #1054
- Pending transfer funds now stay reflected in the total balance without temporarily inflating it while moving between savings and spending. #1058
- Fixed a raw technical error appearing when a locked Trezor blocks connecting or signing; Bitkit now asks you to unlock the device and retries automatically. #1065
- Improved Trezor pairing, Bluetooth recovery, connection and unlock guidance, transfer cancellation, and signed-broadcast retries. #1067
- Hardware wallet transfer sign screens now show the on-chain mining fee and total outflow that matches Trezor confirmation. #1071
- Tapping Connect on the Electrum server settings screen no longer freezes the app when the host field contains a long or malformed value. #1077
- Fixed a crash that could occur when showing the backup-failure notification in some languages. #1080
- Instant-channel payment notifications now appear once with a correctly formatted amount, and opening a regular channel no longer shows a misleading "payment received" notification. #787
- The amount number pad now caps entry at the available maximum on the send, LNURL, transfer, and channel-funding screens, stays usable at a zero balance, and lets you delete down from an over-cap amount. #908
- Home-screen widgets no longer render too tall on Android 10 and 11, matching the size shown on newer Android versions. #994
- Fixed private contact payment preferences so they only show confirmed endpoint publication or removal state. #997





## [2.3.2] - 2026-07-14

### Fixed
- Improved Lightning payment route selection reliability. #1052

## [2.3.1] - 2026-06-26

### Fixed
- Improved LNURL-pay invoice validation. #1048
- Improved LNURL-pay payment handling. #1051

## [2.3.0] - 2026-06-05

### Added
- Added Trezor hardware wallet support for connecting devices, signing messages, and managing on-chain transactions. #792
- Connection issues overlay with connectivity fixes across Send, Receive, and Transfer flows #878
- Home screen widgets foundation with Glance, including price widget as the first implementation #895
- Return to Bitkit after Pubky Ring approval, cancellation, or error callbacks #917
- Headlines home screen widget with v61 wide and compact layouts, including redesigned in-app preview and edit screens #919
- Bitcoin Blocks home screen widget with v61 wide and compact layouts, including redesigned in-app preview and edit screens #922
- Support public Paykit contact payments. #924
- Bitcoin Facts home screen widget with v61 wide and compact layouts, including redesigned in-app facts card and preview screen #926
- Bitcoin Weather home screen widget with v61 wide and compact layouts, including redesigned in-app weather card, preview, and edit screens #927
- Added private Paykit contact payments with dedicated contact endpoints, rotation, cleanup, and restore-safe address reservations. #936
- Added contact payment flows, activity contact attribution, and payment preference controls for private payments. #945
- Added BTCPay wallet connection support for sharing Bitcoin receive descriptors. #961
- Added a Legacy Recovery option in developer settings to help recover funds from affected legacy channel closes. #974
- Home widgets can now be resized between compact and wide from the preview sheet, with a redesigned two-column grid, inline edit mode, and an interactive compact calculator. #985

### Changed
- Redesign price widget with v61 wide and compact layouts, new preview and edit screens, and tap-to-edit behavior #914
- Activity, Shop and Settings now keep their tabs pinned with a drop shadow as content scrolls behind them, the Add Widget button shows its icon, and the Shop list has more bottom spacing. #916
- Redesigned the Bitcoin Calculator widget to v61 design and replaced the OS keyboard with a dark-themed in-app numpad #942
- Hide experimental Paykit profile, contacts, and contact payment controls behind a developer setting. #954
- The add widget experience now opens as a bottom-sheet flow with in-sheet previews instead of full-screen picker pages. #972
- Improve Pubky profile restore, contact editing, and contact routing flows #905

### Fixed
- Fix Spending and Savings screens scrolling behind top bar and add gradient fade effect #892
- Align currency settings and calculator widget behavior with iOS #884
- Align onboarding slides and Create Wallet screen image size, spacing, and dots layout with iOS #904
- Align tab colors, Show details button, notifications bell figure, and home activity count with iOS #907
- Payment QR scans now route reliably and avoid unnecessary delays when Lightning channels are unavailable. #925
- Fix gift card flow showing false-positive confetti when the LSP payment fails, and re-opening unexpectedly after an app language change. #929
- Improved public contact payment flows for manual Pubky entry, add-contact payments, and RBF activity display. #931
- Fix several OS widget issues including an intermittent crash when removing or cancelling a home screen widget, ordering of widget options, and the color of disabled checkboxes in widget configuration screens. #935
- Improved OS widgets so previews, settings, currency display, and OS-home interactions match v61 design iteration. #952
- Improved logs, support diagnostics and channel peer recovery after wallet restore. #969
- The Support page now shows the current copyright year automatically. #971
- Android home-screen widgets now refresh after unlocking and keep retrying with backoff while connectivity is still coming back. #978
- Improved BTCPay setup link handling so Bitkit opens supported connection links reliably and shows clearer setup errors. #979
- Bitkit no longer crashes when Android stops the background Lightning node service. #987
- Bitkit now handles unexpected native on-chain lookup failures without crashing. #989
- New widgets now open on compact size in the preview carousel, matching iOS, and the add-widgets list keeps its scroll position when navigating back. #990
- Fix probe results and add keysend probes #920
- Align top bar back arrow and passphrase input cursor/placeholder with iOS #906
- Polish Terms of Use screen padding to match iOS #903

## [2.2.0] - 2026-04-07

### Fixed
- Retouch Primary, Secondary, and Tertiary buttons styling #887
- Avoid msat truncation when paying invoices and LNURL callbacks #879
- Fix ANR on RGS server settings screen caused by catastrophic regex backtracking #880
- Fix crash when returning app to foreground on Receive screen #875
- Show loading state on Spending tab when node is not running #875

### Added
- Transfer from Savings button on empty Spending screen when savings balance exists #882
- Pubky profile onboarding with contact sync, import, and editing #824
- Lightning Connections empty state with onboarding screen #857
- Unified PIN management screen (enable/disable/change in one place) #857
- Support entry in drawer menu #857
- Brand endorsement row (Synonym + Tether logos) in Support screen #857
- Reset Widgets and Reset Suggestions Cards options in Widgets settings #857
- Diagonal orange footer background in Support screen #857
- Mnemonic warning text transitions on reveal #857

### Changed
- Show end of address on Receive Bitcoin screen using middle ellipsis truncation #886
- Update funding screen: replace Advanced with Manual Setup, fix Use Other Wallet navigation to open amount entry, and add Fund Wallet button to no-funds dialog #885
- Updated design of the success screen in the manual channel setup flow #883
- Unified send flow with payment method switcher, details toggle, Lightning support for BIP21 payments, and improved fee rate defaults #863
- Settings redesigned with tabbed navigation (General/Security/Advanced) with swipe support #857
- Icons added to all settings rows for faster scanning #857
- Selected values displayed on right side of settings rows #857
- Support screen redesigned with About content merged in #857
- Backup and Reset moved into Security tab #857
- PIN flow reworked into sheet-based enable/disable/change #857
- Social links simplified with Brand tint #857
- Mnemonic warning updated with new copy and red styling #857
- Security title changed from "Security and Privacy" to "Security" #857
- Language model updated to use string resources for "System Settings" #857

### Removed
- About screen (content merged into Support) #857
- Standalone General, Security, and Advanced settings screens (merged into tabs) #857

[Unreleased]: https://github.com/synonymdev/bitkit-android/compare/v3.2.0...HEAD
[3.2.0]: https://github.com/synonymdev/bitkit-android/compare/v3.1.1...v3.2.0
[3.1.1]: https://github.com/synonymdev/bitkit-android/compare/v3.1.0...v3.1.1
[3.1.0]: https://github.com/synonymdev/bitkit-android/compare/v3.0.1...v3.1.0
[3.0.1]: https://github.com/synonymdev/bitkit-android/compare/v3.0.0..v3.0.1
[3.0.0]: https://github.com/synonymdev/bitkit-android/compare/v2.5.1..v3.0.0
[2.5.1]: https://github.com/synonymdev/bitkit-android/compare/v2.5.0...v2.5.1
[2.5.0]: https://github.com/synonymdev/bitkit-android/compare/v2.4.1...v2.5.0
[2.4.1]: https://github.com/synonymdev/bitkit-android/compare/v2.4.0...v2.4.1
[2.4.0]: https://github.com/synonymdev/bitkit-android/compare/v2.3.2...v2.4.0
[2.3.2]: https://github.com/synonymdev/bitkit-android/compare/v2.3.1...v2.3.2
[2.3.1]: https://github.com/synonymdev/bitkit-android/compare/v2.3.0...v2.3.1
[2.3.0]: https://github.com/synonymdev/bitkit-android/compare/v2.2.0...v2.3.0
[2.2.0]: https://github.com/synonymdev/bitkit-android/compare/v2.1.2...v2.2.0
