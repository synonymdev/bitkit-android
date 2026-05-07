# RN ↔ Android screens mapping

Legend: RN = React Native screen, Android = Compose screen

## Wallet
| RN | Android |
| - | - |
| Home.tsx | HomeScreen.kt |
| ActivitySavings.tsx | SavingsWalletScreen.kt |
| ActivitySpending.tsx | SpendingWalletScreen.kt |

## Activity
| RN | Android |
| - | - |
| ActivityFiltered.tsx | AllActivityScreen.kt |
| ActivityDetail.tsx | ActivityDetailScreen.kt + ActivityExploreScreen.kt |

## Send
| RN | Android |
| - | - |
| Send/Amount.tsx | SendAmountScreen.kt |
| Recipient.tsx | SendRecipientScreen.kt |
| Address.tsx | SendAddressScreen.kt |
| ReviewAndSend.tsx | SendConfirmScreen.kt |
| FeeRate.tsx | SendFeeRateScreen.kt |
| FeeCustom.tsx | SendFeeCustomScreen.kt |
| CoinSelection.tsx | SendCoinSelectionScreen.kt |
| SendPinPad.tsx | SendPinCheckScreen.kt |
| Quickpay.tsx | SendQuickPayScreen.kt |
| Tags.tsx | AddTagScreen.kt |
| Error.tsx | SendErrorScreen.kt |
| Pending.tsx | `todo` |
| Send/Success.tsx | `todo` |

## Receive
| RN | Android |
| - | - |
| ReceiveDetails.tsx | EditInvoiceScreen.kt |
| ReceiveAmount.tsx | ReceiveAmountScreen.kt |
| ReceiveQR.tsx | ReceiveQrScreen.kt |
| ReceiveConnect.tsx | ReceiveConfirmScreen.kt |
| ReceiveGeoBlocked.tsx | LocationBlockScreen.kt |
| Receive/Liquidity.tsx | ReceiveLiquidityScreen.kt |

## Scanner
| RN | Android |
| - | - |
| MainScanner.tsx | QrScanningScreen.kt |

## Transfer
| RN | Android |
| - | - |
| TransferIntro.tsx | TransferIntroScreen.kt |
| SpendingIntro.tsx | SpendingIntroScreen.kt |
| SpendingConfirm.tsx | SpendingConfirmScreen.kt |
| SavingsIntro.tsx | SavingsIntroScreen.kt |
| SavingsConfirm.tsx | SavingsConfirmScreen.kt |
| SavingsProgress.tsx | SavingsProgressScreen.kt |
| SavingsAdvanced.tsx | SavingsAdvancedScreen.kt |
| SpendingAmount.tsx | SpendingAmountScreen.kt |
| Funding.tsx | FundingScreen.kt |
| FundingAdvanced.tsx | FundingAdvancedScreen.kt |
| SettingUp.tsx | SettingUpScreen.kt |
| Transfer/Liquidity.tsx | LiquidityScreen.kt |
| Availability.tsx | SavingsAvailabilityScreen.kt |

## External Node / LNURL Channel
| RN | Android |
| - | - |
| Connection.tsx | ExternalConnectionScreen.kt |
| ExternalNode/Amount.tsx | ExternalAmountScreen.kt |
| Confirm.tsx | ExternalConfirmScreen.kt |
| ExternalNode/Success.tsx | ExternalSuccessScreen.kt |
| LNURLChannel.tsx | LnurlChannelScreen.kt |

## Lnurl
| RN | Android |
| - | - |
| LNURLWithdraw/Amount.tsx | SendAmountScreen.kt |
| LNURLWithdraw/Confirm.tsx | WithdrawConfirmScreen.kt |
| `n/a` | WithdrawErrorScreen.kt |
| `n/a` | LnurlAuthSheet.kt |

## Settings
| RN | Android |
| - | - |
| Settings/index.tsx | SettingsScreen.kt |
| General/index.tsx | GeneralSettingsScreen.kt |
| Currencies/index.tsx | LocalCurrencySettingsScreen.kt |
| Unit/index.tsx | DefaultUnitSettingsScreen.kt |
| Tags/index.tsx | TagsSettingsScreen.kt |
| Advanced/index.tsx | AdvancedSettingsScreen.kt |
| AddressTypePreference/index.tsx | `n/a` |
| BitcoinNetworkSelection.tsx | `n/a` |
| CoinSelectPreference/index.tsx | CoinSelectPreferenceScreen.kt |
| AddressViewer/index.tsx | AddressViewerScreen.kt |
| GapLimit/index.tsx | `n/a` |
| About/index.tsx | AboutScreen.kt |
| AppStatus/index.tsx | AppStatusScreen.kt |
| Widgets/index.tsx | AddWidgetsScreen.kt / WidgetsIntroScreen.kt |
| WebRelay/index.tsx | `n/a` |
| TransactionSpeed/index.tsx | TransactionSpeedSettingsScreen.kt |
| CustomFee.tsx | CustomFeeSettingsScreen.kt |
| QuickpayIntro.tsx | QuickPayIntroScreen.kt |
| QuickpaySettings.tsx | QuickPaySettingsScreen.kt |
| RGSServer/index.tsx | RgsServerScreen.kt |
| SupportSettings/index.tsx | SupportScreen.kt |
| ReportIssue/index.tsx | ReportIssueScreen.kt |
| FormSuccess.tsx | ReportIssueResultScreen.kt |
| DevSettings/index.tsx | DevSettingsScreen.kt |
| LdkDebug.tsx | `n/a` |
| Channels.tsx | LightningConnectionsScreen.kt |
| ChannelDetails.tsx | ChannelDetailScreen.kt |
| CloseConnection.tsx | CloseConnectionScreen.kt |
| LightningNodeInfo.tsx | NodeInfoScreen.kt |
| BackupSettings/index.tsx | BackupSettingsScreen.kt |

## Backup & Recovery
| RN | Android |
| - | - |
| Warning.tsx | WarningScreen.kt |
| Backup/Success.tsx | SuccessScreen.kt |
| ShowPassphrase.tsx | ShowPassphraseScreen.kt |
| ShowMnemonic.tsx | ShowMnemonicScreen.kt |
| Backup/MultipleDevices.tsx | MultipleDevicesScreen.kt |
| Metadata.tsx | MetadataScreen.kt |
| ConfirmPassphrase.tsx | ConfirmPassphraseScreen.kt |
| ConfirmMnemonic.tsx | ConfirmMnemonicScreen.kt |
| ResetAndRestore.tsx | ResetAndRestoreScreen.kt |

## Onboarding
| RN | Android |
| - | - |
| Welcome.tsx | OnboardingSlidesScreen.kt / IntroScreen.kt |
| Slideshow.tsx | OnboardingSlidesScreen.kt |
| Passphrase.tsx | CreateWalletWithPassphraseScreen.kt |
| RestoreFromSeed.tsx | RestoreWalletScreen.kt |
| Loading.tsx | InitializingWalletView.kt |
| Onboarding/MultipleDevices.tsx | WarningMultipleDevicesScreen.kt |
| TermsOfUse.tsx | TermsOfUseScreen.kt |
| CreateWallet.tsx | WalletRestoreSuccessView.kt + WalletRestoreErrorView.kt |

## Profile & Contacts
| RN | Android |
| - | - |
| Contacts.tsx | `todo` |
| Contact.tsx | `todo` |
| Profile.tsx | ProfileScreen.kt |
| ProfileEdit.tsx | `n/a` |
| ProfileOnboarding.tsx | ProfileIntroScreen.kt / PubkyRingAuthScreen.kt |
| ProfileLink.tsx | `n/a` |

## Widgets
| RN | Android |
| - | - |
| Widget.tsx | widgets/*Card.kt |
| WidgetEdit.tsx | widgets/*EditScreen.kt |
| WidgetsOnboarding.tsx | WidgetsIntroScreen.kt |
| WidgetsSuggestions.tsx | AddWidgetsScreen.kt |

## Shop
| RN | Android |
| - | - |
| ShopIntro.tsx | ShopIntroScreen.kt |
| ShopDiscover.tsx | ShopDiscoverScreen.kt |
| ShopMain.tsx | ShopWebViewScreen.kt |

## Sheets
| RN | Android |
| - | - |
| ReceivedTransaction.tsx | NewTransactionSheet.kt |
| TransferFailed.tsx | todo |
| AppUpdate.tsx | `todo` |
