# Screens map

Every `*Screen.kt` under `app/src/main/java`, grouped by feature package, with the frame that renders it on the `Bitkit - Handoff vNN` page of the Bitkit Figma file (`ltqvnKiejWj0JQiqtDf2JJ`, always the highest `vNN`).

Columns:

- Android: the Compose screen file. Every `*Screen.kt` in the app has exactly one row; `ScreensMapTest` fails when a screen is added or removed without updating this file. A row is the only requirement, a design is not.
- Figma: `Flow › Frame`, where Flow is the `SectionTitle` above the frame on the handoff page and Frame is the top-level frame name. `todo` when the screen shipped ahead of its design; `n/a` when no design is intended (dev tools, debug screens).

Frame names are stable across handoff iterations; node ids are not, so the map lists names only. Sheets, dialogs, and composables without the `Screen` suffix are out of scope.

## appwidget/config

| Android | Figma |
| - | - |
| AppWidgetConfigScreen.kt | `n/a` |

## ui

| Android | Figma |
| - | - |
| NodeInfoScreen.kt | Settings > Advanced › Advanced > Node ID |

## ui/components

| Android | Figma |
| - | - |
| AuthCheckScreen.kt | Active user › Enter PIN |

## ui/onboarding

| Android | Figma |
| - | - |
| CreateWalletScreen.kt | New user › Wallet set up |
| CreateWalletWithPassphraseScreen.kt | New user › Wallet set up advanced |
| IntroScreen.kt | New user › Intro |
| OnboardingSlidesScreen.kt | New user › Onboarding Self-Custody / Onboarding Lightning / Onboarding Privacy |
| RestoreWalletScreen.kt | Restore wallet › Restore wallet |
| TermsOfUseScreen.kt | New user › Acknowledgements |
| WarningMultipleDevicesScreen.kt | Multiple Devices › Multiple Devices Warning |

## ui/screens

| Android | Figma |
| - | - |
| CriticalUpdateScreen.kt | Update › Forced update |
| MigrationLoadingScreen.kt | Update › Forced migration |
| SplashScreen.kt | New user › Splash and loading screen |

## ui/screens/common

| Android | Figma |
| - | - |
| ComingSoonScreen.kt | Soon › Coming Soon |

## ui/screens/contacts

| Android | Figma |
| - | - |
| AddContactScreen.kt | Contacts › Add new contact |
| ContactActivityScreen.kt | Contacts › Contact activity |
| ContactDetailScreen.kt | Contacts › Contact details |
| ContactImportOverviewScreen.kt | Profile › Profile Contacts Import |
| ContactImportSelectScreen.kt | Profile › Profile Contacts Import Select |
| ContactsIntroScreen.kt | Contacts › My contacts |
| ContactsScreen.kt | Contacts › My contacts list |
| EditContactScreen.kt | Contacts › Contact Edit |

## ui/screens/paymentrequests

| Android | Figma |
| - | - |
| CreatePaymentRequestScreen.kt | Payment Request (from Receive > Contacts OR Contact profile OR Payment Requests) › Request payment enter amount |
| IncomingPaymentRequestDetailsScreen.kt | `todo` |
| PaymentRequestsScreen.kt | Payment Requests › Payment Requests |

## ui/screens/profile

| Android | Figma |
| - | - |
| CreateProfileScreen.kt | Profile › Profile Create |
| EditProfileScreen.kt | Profile › Profile Edit |
| PayContactsScreen.kt | Profile › Pay Contacts Setup |
| ProfileIntroScreen.kt | Profile › Profile Intro |
| ProfileScreen.kt | Profile › Profile active |
| PubkyChoiceScreen.kt | Profile › Profile Create or use existing |

## ui/screens/recovery

| Android | Figma |
| - | - |
| RecoveryMnemonicScreen.kt | `n/a` |
| RecoveryModeScreen.kt | `n/a` |

## ui/screens/scanner

| Android | Figma |
| - | - |
| QrScanningScreen.kt | Send (QuickPay) › Scan QR invoice Quickpay |

## ui/screens/settings

| Android | Figma |
| - | - |
| DevSettingsScreen.kt | `n/a` |
| FeeSettingsScreen.kt | `n/a` |
| LdkDebugScreen.kt | `n/a` |
| LegacyRnRecoveryScreen.kt | `n/a` |
| ProbingToolScreen.kt | `n/a` |
| VssDebugScreen.kt | `n/a` |

## ui/screens/shop

| Android | Figma |
| - | - |
| ShopIntroScreen.kt | Shop (Bitrefill & BTCMaps) › Shop Onboarding |
| shopDiscover/ShopDiscoverScreen.kt | Shop (Bitrefill & BTCMaps) › Shop Discover |
| shopWebView/ShopWebViewScreen.kt | Shop (Bitrefill & BTCMaps) › Shop Gift Cards |

## ui/screens/subscriptions

| Android | Figma |
| - | - |
| CreateSubscriptionScreen.kt | Subscriptions › Create Subscription / Choose Subscription Recipient / Sent Subscription Proposal |
| SubscriptionsScreen.kt | Subscriptions › Subscriptions Intro / Subscriptions overview |

## ui/screens/transfer

| Android | Figma |
| - | - |
| FundingAdvancedScreen.kt | Manual Setup › Lightning Manual Setup |
| FundingScreen.kt | Lightning Onboarding From Suggestion Card › Lightning onboarding Fund |
| LiquidityScreen.kt | Transfer to Spending Balance From Savings (Channel Opening) › Transfer to Spending Receiving Capacity |
| SavingsAdvancedScreen.kt | Transfer to Savings (Channel Closure) › Transfer to savings select funds |
| SavingsAvailabilityScreen.kt | Transfer to Savings (Channel Closure) › Transfer warning |
| SavingsConfirmScreen.kt | Transfer to Savings (Channel Closure) › Transfer all funds to savings review |
| SavingsIntroScreen.kt | Transfer to Savings (Channel Closure) › Transfer to savings |
| SavingsProgressScreen.kt | Transfer to Savings (Channel Closure) › Transfer to savings progress 2 |
| SettingUpScreen.kt | Funds In Transfer › Transfer to Spending Progress 1 |
| SpendingAdvancedScreen.kt | Transfer to Spending Balance From Savings (Channel Opening) › Transfer to Spending Confirm Advanced |
| SpendingAmountScreen.kt | Transfer to Spending Balance From Savings (Channel Opening) › Transfer to Spending Input |
| SpendingConfirmScreen.kt | Transfer to Spending Balance From Savings (Channel Opening) › Transfer to Spending Confirm |
| SpendingIntroScreen.kt | Transfer to Spending Balance From Savings (Channel Opening) › Transfer to Spending Intro |
| TransferIntroScreen.kt | Lightning Onboarding From Suggestion Card › Lightning onboarding Intro |

## ui/screens/transfer/external

| Android | Figma |
| - | - |
| ExternalAmountScreen.kt | Manual Setup › Lightning Manual Setup Input |
| ExternalConfirmScreen.kt | Manual Setup › Lightning Manual Setup Confirm |
| ExternalConnectionScreen.kt | Manual Setup › Lightning Manual Setup |
| ExternalSuccessScreen.kt | Manual Setup › Lightning Manual Done |
| LnurlChannelScreen.kt | Third-party LSP › LSP LNBIG |

## ui/screens/transfer/hardware

| Android | Figma |
| - | - |
| SpendingAmountHwScreen.kt | Transfer to Spending Balance From HW Device › Transfer to Spending Input HW |
| SpendingHwSignScreen.kt | Transfer to Spending Balance From HW Device › Transfer to Spending Confirm HW |
| SpendingHwSignedScreen.kt | Transfer to Spending Balance From HW Device › Transfer to Spending Confirmed HW |

## ui/screens/trezor

| Android | Figma |
| - | - |
| TrezorScreen.kt | Connect Hardware › Hardware Connect Device |

## ui/screens/wallets

| Android | Figma |
| - | - |
| HardwareWalletScreen.kt | Hardware › Wallet Hardware |
| HomeScreen.kt | Wallet On-chain › Wallet BTC |
| SavingsWalletScreen.kt | Savings, Spending, Hardware Balances › Savings Populated |
| SpendingWalletScreen.kt | Savings, Spending, Hardware Balances › Spending Ready |

## ui/screens/wallets/activity

| Android | Figma |
| - | - |
| ActivityAssignContactScreen.kt | Wallet Lightning - Transfer to Spending Balance › TX Lightning Assign Contact |
| ActivityDetailScreen.kt | Transaction Details › TX On-chain Confirmed / TX Lightning |
| ActivityExploreScreen.kt | Transaction Details › TX On-chain Technical |
| AllActivityScreen.kt | Activity › Activity |

## ui/screens/wallets/receive

| Android | Figma |
| - | - |
| EditInvoiceScreen.kt | Send (Contact) (Lightning) › Edit invoice populated |
| LocationBlockScreen.kt | Send (Enter Manually) › CJIT Location Block |
| ReceiveAmountScreen.kt | Receive CJIT Onboarding › CJIT Enter amount |
| ReceiveConfirmScreen.kt | Send (Enter Manually) › CJIT Fee to connect |
| ReceiveLiquidityScreen.kt | Send (Hardware Device) (On-chain) › Liquidity fee |
| ReceiveQrScreen.kt | Receive Lightning Active › Receive Auto (Unified QR) |

## ui/screens/wallets/send

| Android | Figma |
| - | - |
| AddTagScreen.kt | Send (Paste) (On-chain) › Tag |
| HwSendSignScreen.kt | Send (Hardware Device) (On-chain) › Sign with Hardware Device |
| SendAddressScreen.kt | Send (Paste) (On-chain) › Address and Invoice |
| SendAmountScreen.kt | Send (Paste) (On-chain) › Set amount |
| SendCoinSelectionScreen.kt | Send (Paste) (On-chain) › Coin selection |
| SendConfirmScreen.kt | Send (Paste) (On-chain) › Confirm Send Onchain |
| SendContactSelectScreen.kt | Send (Contact) (Lightning) › Send to contact |
| SendErrorScreen.kt | Send (Paste) (On-chain) › Transaction failed |
| SendFeeCustomScreen.kt | Send (Paste) (On-chain) › Set custom fee |
| SendFeeRateScreen.kt | Send (Paste) (On-chain) › Speed |
| SendPendingScreen.kt | Send (Edge cases) › Transaction pending Lightning |
| SendPinCheckScreen.kt | Active user › Enter PIN |
| SendQuickPayScreen.kt | Send (QuickPay) › QuickPay Paying |
| SendRecipientScreen.kt | Send › Send Bitcoin |

## ui/screens/wallets/suggestion

| Android | Figma |
| - | - |
| BuyIntroScreen.kt | Buy Bitcoin › Buy Bitcoin |

## ui/screens/wallets/withdraw

| Android | Figma |
| - | - |
| WithdrawConfirmScreen.kt | LNURL › LNURL Withdraw |
| WithdrawErrorScreen.kt | LNURL › LNURL Withdraw Failed |

## ui/screens/widgets

| Android | Figma |
| - | - |
| AddWidgetsScreen.kt | Widgets › Add Widgets |
| WidgetsIntroScreen.kt | Widgets › Widgets onboarding |
| blocks/BlocksEditScreen.kt | Widgets › Bitcoin Blocks Widget Settings |
| blocks/BlocksPreviewScreen.kt | Widgets › Bitcoin Blocks wide |
| calculator/CalculatorPreviewScreen.kt | Widgets › Bitcoin Calculator wide |
| facts/FactsPreviewScreen.kt | Widgets › Bitcoin Facts wide |
| headlines/HeadlinesEditScreen.kt | Widgets › Bitcoin Headlines Widget Settings |
| headlines/HeadlinesPreviewScreen.kt | Widgets › Bitcoin Headlines wide |
| price/PriceEditScreen.kt | Widgets › Bitcoin Price Widget Feed Default |
| price/PricePreviewScreen.kt | Widgets › Bitcoin Price wide |
| suggestions/SuggestionsPreviewScreen.kt | Widgets › Bitcoin Suggestions |
| weather/WeatherEditScreen.kt | Widgets › Bitcoin Weather Widget Settings |
| weather/WeatherPreviewScreen.kt | Widgets › Bitcoin Weather wide |

## ui/settings

| Android | Figma |
| - | - |
| BackupSettingsScreen.kt | Back up wallet › Data Backups |
| BlocktankRegtestScreen.kt | `n/a` |
| ChannelOrdersScreen.kt | `n/a` |
| LanguageSettingsScreen.kt | Settings > General › Settings Language |
| LogsScreen.kt | `n/a` |
| SettingsScreen.kt | Settings Categories › Settings General |
| SwapsScreen.kt | `n/a` |

## ui/settings/advanced

| Android | Figma |
| - | - |
| AddressTypePreferenceScreen.kt | Settings > Advanced › Advanced > Bitcoin address type |
| AddressViewerScreen.kt | Settings > Advanced › Advanced > Bitcoin UTXOs |
| CoinSelectPreferenceScreen.kt | Settings > Advanced › Advanced > Coin selection |
| ElectrumConfigScreen.kt | Settings > Advanced › Advanced > Electrum server |
| RgsServerScreen.kt | `n/a` |
| WatchOnlyAccountsScreen.kt | Settings > Advanced › Advanced > Payment Accounts |

## ui/settings/appStatus

| Android | Figma |
| - | - |
| AppStatusScreen.kt | Support & App Status › App Status OK |

## ui/settings/backgroundPayments

| Android | Figma |
| - | - |
| BackgroundPaymentsIntroScreen.kt | Settings > General › BackgroundPayments Intro |

## ui/settings/backups

| Android | Figma |
| - | - |
| BackupIntroScreen.kt | Back up wallet › Backup Intro |
| ConfirmMnemonicScreen.kt | Back up wallet › Backup Recovery Phrase Confirm |
| ConfirmPassphraseScreen.kt | Back up wallet › Backup Passphrase Confirm |
| MetadataScreen.kt | Back up wallet › Bitcoin wallet backup data |
| MultipleDevicesScreen.kt | Back up wallet › Bitcoin wallet back multidevice |
| ResetAndRestoreScreen.kt | Back up wallet › Reset wallet |
| ShowMnemonicScreen.kt | Back up wallet › Backup Recovery Phrase Displayed |
| ShowPassphraseScreen.kt | Back up wallet › Backup Passphrase |
| SuccessScreen.kt | Back up wallet › Backup Recovery Phrase Confirmed |
| WarningScreen.kt | Back up wallet › Backup Passphrase Keep Safe |

## ui/settings/general

| Android | Figma |
| - | - |
| DefaultUnitSettingsScreen.kt | Settings > General › Setting Default Unit |
| HardwareWalletsSettingsScreen.kt | Settings > General › Hardware Wallets |
| LocalCurrencySettingsScreen.kt | Settings > General › Settings Local Currency |
| TagsSettingsScreen.kt | Settings > General › Tags |
| WidgetsSettingsScreen.kt | Settings > General › Widgets |

## ui/settings/lightning

| Android | Figma |
| - | - |
| ChannelDetailScreen.kt | Settings > Advanced › Connection details |
| CloseConnectionScreen.kt | Settings > Advanced › Close connection |
| LightningConnectionsScreen.kt | Settings > Advanced › Lightning Connections overview |

## ui/settings/pin

| Android | Figma |
| - | - |
| PinBiometricsScreen.kt | Security › Use Face ID |
| PinChooseScreen.kt | Security › Create passcode |
| PinConfirmScreen.kt | Security › Create passcode |
| PinManagementScreen.kt | Settings > Security › PIN Enabled |
| PinPromptScreen.kt | Settings > Security › PIN Change Enter Current |
| PinResultScreen.kt | Settings > Security › PIN Change Succes |

## ui/settings/quickPay

| Android | Figma |
| - | - |
| QuickPayIntroScreen.kt | Settings > General › QuickPay Intro |
| QuickPaySettingsScreen.kt | Settings > General › QuickPay Settings |

## ui/settings/support

| Android | Figma |
| - | - |
| ReportIssueResultScreen.kt | Support & App Status › Support Report Success |
| ReportIssueScreen.kt | Support & App Status › Support Report |
| SupportScreen.kt | Support & App Status › Support |

## ui/settings/transactionSpeed

| Android | Figma |
| - | - |
| CustomFeeSettingsScreen.kt | Send (Paste) (On-chain) › Set custom fee |
| TransactionSpeedSettingsScreen.kt | Settings > General › Speed |
