# Security & Privacy Settings Implementation Plan

## Overview

This document outlines the plan to implement missing security and privacy settings in the Android app to achieve feature parity with the React Native app.

## Current Status Analysis

### ✅ Already Implemented
- PIN setup/disable
- PIN change  
- PIN on launch
- PIN on idle
- PIN for payments
- Biometric authentication

### ❌ Missing Features
1. **Swipe Balance to Hide** (`enableSwipeToHideBalance`) - ✅ **COMPLETED**
2. **Hide Balance on Open** (`hideBalanceOnOpen`) - ✅ **COMPLETED**
3. **Auto Read Clipboard** (`enableAutoReadClipboard`) - ⏳ **IN PROGRESS**
4. **Warn when sending over $100** (`enableSendAmountWarning`) - ⏳ **IN PROGRESS**

### ✅ Infrastructure Ready
- **String Resources**: All required strings already exist in `strings.xml`
- **UI Components**: `SettingsSwitchRow` and `SettingsButtonRow` components available
- **Navigation**: Auth check flow and navigation patterns established

## Implementation Progress

### ✅ Phase 1: Data Layer - COMPLETED

#### 1.1 Update `SettingsData` class ✅
**File**: `bitkit-android/app/src/main/java/to/bitkit/data/SettingsStore.kt`

Added missing properties to the `SettingsData` data class:
```kotlin
@Serializable
data class SettingsData(
    // ... existing properties ...
    val enableSwipeToHideBalance: Boolean = true,
    val hideBalance: Boolean = false,
    val hideBalanceOnOpen: Boolean = false,
    val enableAutoReadClipboard: Boolean = false,
    val enableSendAmountWarning: Boolean = false,
)
```

#### 1.2 Update `SettingsViewModel` ✅
**File**: `bitkit-android/app/src/main/java/to/bitkit/viewmodels/SettingsViewModel.kt`

Added StateFlow properties and setter methods for all new settings.

### ✅ Phase 2: UI Layer - COMPLETED

#### 2.1 Update `SecuritySettingsScreen` ✅
**File**: `bitkit-android/app/src/main/java/to/bitkit/ui/settings/SecuritySettingsScreen.kt`

Added new state flows and click handlers for all 4 missing settings.

#### 2.2 Update `SecuritySettingsContent` composable ✅
Added the missing switch rows in the correct order matching React Native app.

### ✅ Phase 3: Business Logic Integration - COMPLETED FOR SWIPE TO HIDE

#### 3.1 Balance Hiding Functionality ✅
**Files created/modified**:
- ✅ `SwipeToHideDetector.kt` - Generic swipe detection component
- ✅ `BalanceHeaderView.kt` - Updated to support swipe gestures and balance hiding
- ✅ `ic_eye.xml` - Created eye icon drawable for show balance button
- ✅ `ContentView.kt` - Added startup logic for `hideBalanceOnOpen`

**Features implemented**:
- ✅ Swipe left/right on balance to toggle visibility
- ✅ Balance displays dots when hidden
- ✅ Eye icon appears when balance is hidden for direct tap to reveal
- ✅ App startup automatically hides balance if `hideBalanceOnOpen=true`
- ✅ Proper dependency handling (hideBalanceOnOpen only visible when swipe enabled)
- ✅ **Symbol Display Fix**: Symbols (₿, $) now correctly show even when balance is hidden (matches React Native)

#### 3.2 Clipboard Auto-Reading ⏳ TODO
**Files to create/modify**:
- `ClipboardMonitorService.kt` - Monitors clipboard for Bitcoin/Lightning data
- Update scanner/send screens to integrate clipboard detection
- Handle permission requests for clipboard access

#### 3.3 Amount Warning ⏳ TODO
**Files to create/modify**:
- Update send flow to check amount thresholds
- Create warning dialog for large amounts
- Integrate with exchange rate conversion

### Phase 4: Testing & Polish ⏳ TODO

#### 4.1 Unit Tests
- Test `SettingsViewModel` new methods
- Test data store updates
- Test business logic components

#### 4.2 UI Tests
- Test settings screen interactions
- Test balance hiding functionality
- Test clipboard integration
- Test amount warning dialogs

#### 4.3 Integration Tests
- Test end-to-end flows
- Test settings persistence
- Test app startup behavior

## Settings Order (Matching React Native)

1. **Swipe balance to hide** (switch) ✅
2. **Hide balance on open** (switch - hidden if swipe disabled) ✅
3. **Read clipboard** (switch) ✅ UI only
4. **Warn when sending over $100** (switch) ✅ UI only
5. **PIN setup/disable** (button) ✅
6. **PIN change** (button - hidden if no PIN) ✅
7. **PIN on launch** (switch - hidden if no PIN) ✅
8. **PIN on idle** (switch - hidden if no PIN) ✅
9. **PIN for payments** (switch - hidden if no PIN) ✅
10. **Use biometrics** (switch - hidden if no PIN or unsupported) ✅

## Dependencies & Business Rules

### ✅ Swipe to Hide Balance - COMPLETED
- ✅ When disabled, automatically disable `hideBalanceOnOpen`
- ✅ Hide the "Hide balance on open" setting when swipe is disabled
- ✅ Reset any currently hidden balance state
- ✅ Swipe gesture detection working
- ✅ Balance hiding with dots display working
- ✅ Eye icon for manual reveal working

### ✅ Hide Balance on Open - COMPLETED
- ✅ Only visible when `enableSwipeToHideBalance` is true
- ✅ Controls whether balance is hidden when app starts
- ✅ Startup logic implemented in ContentView

### ⏳ Auto Read Clipboard - TODO
- Requires clipboard permission
- Should detect Bitcoin addresses, Lightning invoices, and payment requests
- Show redirect dialog when relevant data is detected

### ⏳ Amount Warning - TODO
- Warn when sending amount > $100 USD equivalent
- Use current exchange rates for conversion
- Show confirmation dialog before proceeding

## Existing String Resources

All required strings are already available in `strings.xml`:

```xml
<string name="settings__security__swipe_balance_to_hide">Swipe balance to hide</string>
<string name="settings__security__hide_balance_on_open">Hide balance on open</string>
<string name="settings__security__clipboard">Read clipboard for ease of use</string>
<string name="settings__security__warn_100">Warn when sending over $100</string>
```

## Files Modified/Created

### ✅ Core Files - COMPLETED
- ✅ `bitkit-android/app/src/main/java/to/bitkit/data/SettingsStore.kt`
- ✅ `bitkit-android/app/src/main/java/to/bitkit/viewmodels/SettingsViewModel.kt`
- ✅ `bitkit-android/app/src/main/java/to/bitkit/ui/settings/SecuritySettingsScreen.kt`

### ✅ New Files Created - COMPLETED
- ✅ `bitkit-android/app/src/main/java/to/bitkit/ui/components/SwipeToHideDetector.kt`
- ✅ `bitkit-android/app/src/main/res/drawable/ic_eye.xml`

### ✅ Files Updated - COMPLETED
- ✅ `bitkit-android/app/src/main/java/to/bitkit/ui/components/BalanceHeaderView.kt`
- ✅ `bitkit-android/app/src/main/java/to/bitkit/ui/ContentView.kt`

### ⏳ Files to Create/Update - TODO
- `bitkit-android/app/src/main/java/to/bitkit/services/ClipboardMonitorService.kt`
- `bitkit-android/app/src/main/java/to/bitkit/ui/components/AmountWarningDialog.kt`
- Send screens for amount warnings
- Scanner screens for clipboard integration

## Success Criteria

- ✅ **Swipe Balance to Hide**: Fully implemented and functional
- ✅ **Hide Balance on Open**: Fully implemented and functional
- ⏳ **Auto Read Clipboard**: UI implemented, business logic TODO
- ⏳ **Amount Warning**: UI implemented, business logic TODO
- ✅ Settings order matches React Native app exactly
- ✅ Proper dependency handling (hide balance on open visibility)
- ⏳ Business logic integration works end-to-end (partial)
- ⏳ Comprehensive test coverage
- ✅ No breaking changes to existing functionality
- ⏳ Proper error handling and edge cases covered

## Next Steps

1. **Implement Clipboard Auto-Reading**:
   - Create clipboard monitoring service
   - Add clipboard permission handling
   - Integrate with send/scanner screens
   - Add clipboard data detection logic

2. **Implement Amount Warning**:
   - Add amount threshold checking in send flow
   - Create warning dialog component
   - Integrate with exchange rate conversion
   - Add confirmation flow

3. **Add Comprehensive Testing**:
   - Unit tests for all new functionality
   - UI tests for settings interactions
   - Integration tests for end-to-end flows

## Timeline Estimate

- ✅ **Phase 1 (Data Layer)**: 1-2 days - COMPLETED
- ✅ **Phase 2 (UI Layer)**: 1-2 days - COMPLETED
- ✅ **Phase 3a (Swipe to Hide)**: 2-3 days - COMPLETED
- ⏳ **Phase 3b (Clipboard & Amount Warning)**: 3-4 days - TODO
- ⏳ **Phase 4 (Testing & Polish)**: 2-3 days - TODO

**Total Progress**: ~60% complete (6/10 days estimated) 

## React Native Hidden Balance Analysis

### How React Native Handles Hidden Balance Display

From analyzing `bitkit/src/components/Money.tsx` and `bitkit/src/components/BalanceHeader.tsx`:

#### Key Behavior:
1. **Symbol Display**: In React Native, the symbol (₿ or $) is **ALWAYS shown** even when balance is hidden
2. **Dots Logic**: Only the numeric value is replaced with dots, not the symbol
3. **Different Dot Counts**:
   - Display size: `' • • • • • • • • •'` (9 dots with spaces)
   - Other sizes: `' • • • • •'` (5 dots with spaces)
4. **Symbol Position**: Symbol appears before the dots, with proper spacing
5. **Eye Icon**: Appears to the right of the hidden balance for manual reveal

#### React Native Code Analysis:
```tsx
// Money.tsx line 61
const hide = (props.enableHide ?? false) && hideBalance;

// Money.tsx lines 118-124
if (hide) {
    if (size === 'display') {
        text = ' • • • • • • • • •';  // 9 dots for display size
    } else {
        text = ' • • • • •';         // 5 dots for other sizes
    }
}

// Symbol is rendered separately and always shown:
{showSymbol && symbol}  // This always renders when showSymbol=true
<Text color={color}>{text}</Text>  // Only this part shows dots
```

#### BalanceHeader Usage:
```tsx
<Money sats={totalBalance} enableHide={true} symbol={true} />
```

### Android Implementation Issues Found

After reviewing the Android implementation in `BalanceHeaderView.kt`:

#### ❌ Current Problems:
1. **Missing Symbol**: When balance is hidden, the symbol is completely hidden instead of shown
2. **Incorrect Logic**: The `showSymbol` parameter is set to `false` when `hideBalance=true`
3. **Symbol Field**: The `largeRowSymbol` is set to empty string `""` when hidden

#### ❌ Problematic Android Code:
```kotlin
// Current Android implementation (WRONG)
symbol = if (hideBalance) "" else largeRowSymbol,
showSymbol = !hideBalance && showSymbol
```

#### ✅ Correct Behavior Should Be:
```kotlin
// Fixed Android implementation (CORRECT)
symbol = largeRowSymbol,
showSymbol = showSymbol
```

### Implementation Fix Required

The Android `BalanceHeader` composable needs to be updated to:
1. Always show the symbol regardless of `hideBalance` state
2. Only replace the numeric text with dots
3. Use proper dot formatting with spaces
4. Match the exact behavior of React Native's Money component

### ✅ Implementation Fix Applied

**Fixed in**: `bitkit-android/app/src/main/java/to/bitkit/ui/components/BalanceHeaderView.kt`

#### Changes Made:
1. **✅ Large Row Symbol**: Fixed `symbol = largeRowSymbol` (always show)
2. **✅ Large Row showSymbol**: Fixed `showSymbol = showSymbol` (don't hide based on balance)
3. **✅ Small Row Symbol**: Fixed `symbol = smallRowSymbol` (always show)
4. **✅ Preview Added**: Added `PreviewHidden` to show correct hidden balance behavior

#### Before (❌ Incorrect):
```kotlin
symbol = if (hideBalance) "" else largeRowSymbol,
showSymbol = !hideBalance && showSymbol
```

#### After (✅ Correct):
```kotlin
symbol = largeRowSymbol,
showSymbol = showSymbol
```

Now the Android implementation correctly matches React Native behavior:
- ✅ Symbols (₿, $) are always shown even when balance is hidden
- ✅ Only numeric values are replaced with dots
- ✅ Proper dot formatting: "• • • • • • • • •" for large text, "• • • • •" for small text
- ✅ Eye icon appears for manual reveal when balance is hidden

**Current Status**:
- ✅ **Completed**: Swipe Balance to Hide and Hide Balance on Open features (fully functional)
- ⏳ **Partial**: Auto Read Clipboard and Amount Warning (UI implemented, business logic TODO)
- **Progress**: ~60% complete (6/10 estimated days)
